package pl.rynekanaliza;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Auto rynek (zastępuje BK-Rynek): jeden start/stop. Na starcie analiza cen wszystkich przedmiotów (amAnalysis; można ją
 * pominąć), potem snajper ofert z zakładki "Najnowsze", wystawianie przy każdym restarcie rynku, anty-AFK i analiza
 * co 12 min (wtedy auto rynek stoi, a potem sam wraca). Ceny bierze z listy przedmiotów (maxPrice = skup,
 * sellPrice = sprzedaż), które wpisuje analiza (albo autopilot NA ŻYWO).
 *
 * Pętla (co tick gry, a zakup także w każdej klatce okna - onFrame):
 *  - otwiera rynek komendą (domyślnie /ah), ustawia sortowanie "Najnowsze" (PPM na przycisku sortowania, aż ta opcja
 *    będzie zaznaczona),
 *  - przy każdej zmianie okna sprawdza oferty: przedmiot z listy (dopasowanie jak w BK), cena za sztukę (tryb "Unit")
 *    albo cała oferta o ilości kupna (tryb "Sum") NIŻSZA niż skup; skup 0 albo brak = nigdy nie kupuje; własne oferty,
 *    oferty droższe niż saldo konta i niedawno nieudane są pomijane; oferty z listy są podświetlane (zielone = zysk po
 *    sprzedaży co najmniej amHighlightProfit $, czerwone = mniej),
 *  - kupno: LPM na ofercie, w oknie potwierdzenia sprawdza, że to ten sam przedmiot i nie drożej, klika "Potwierdź"
 *    (szukany po nazwie albo zielonym barwniku, zapasowo slot z ustawień) od razu, gdy okno ma zawartość; wynik z czatu
 *    albo z ekwipunku,
 *  - co amRefreshMs odświeża listę (PPM na slocie odświeżania),
 *  - co amRestartSec zamyka rynek, wystawia przedmioty (kupione przez snajpera - licznik toSell - albo wszystkie
 *    z "Wyst.: TAK"; "/wystaw ilość cena" z ceny sprzedaży z listy) i otwiera rynek od nowa; brak miejsca na rynku
 *    (limit wystawionych) = snajpi dalej, wystawia znowu po sprzedaży albo za 5 min; restart także, gdy okno nie zmienia
 *    się przez amStuckSec (zacięcie), nie otwiera się albo zatnie się okno potwierdzenia,
 *  - tryb "Tylko sprzedaż" (amBuy = false): rynek zamknięty, co amRestartSec wystawia przedmioty, anty-AFK i analiza dalej,
 *  - co amAfkMin minut zamyka rynek, skacze amAfkJumps razy i wraca (anty-AFK),
 *  - gdy gracz otworzy inne okno (czat, ekwipunek, panel) - czeka; po jego zamknięciu wraca na rynek.
 */
public final class AutoMarket {
    private AutoMarket() {}

    enum State { OFF, OPEN, WAIT_OPEN, SORT, SORT_WAIT, SNIPE, BUY_WAIT, CONFIRM, RESULT, SELL, AFK, RESTART, HOLD, PAUSED, IDLE }

    /** Zegar (podmieniany w testach). */
    static LongSupplier clock = System::currentTimeMillis;
    /** Analiza wszystkich przedmiotów na starcie (podmieniane w testach); true = ruszyła (auto rynek czeka na nią). */
    static java.util.function.BooleanSupplier startScan = RynekAnalizaMod::startBotScanNow;

    private static boolean on, paused;
    static State state = State.OFF;
    private static long stateAt, waitUntil;

    // rynek
    private static long sessionAt, lastChangeAt, lastRefreshAt, nextSellAt, nextAfkAt;
    private static int lastSig;
    private static boolean changedSinceRefresh, scanNeeded, fullWarned;
    /** Wystawianie przy najbliższym otwarciu rynku (start, restart). */
    private static boolean sellDue;
    private static int openFails, sortClicks, sortBaseSig, buyTimeouts;
    private static boolean sortConfirmed, sortGaveUp;

    /** Próba zakupu jednej oferty. */
    static final class Attempt {
        MarketItem item;
        ItemStack stack;
        String name = "";
        double total, limit;
        int count, slot, baseInv, confirmClicks;
        String id = "";
        long clickAt, confirmAt;
        boolean reclicked;
    }

    static Attempt cur;

    // komunikaty serwera (czas ostatniego komunikatu danego rodzaju)
    private static long chatOkAt = -1, chatFailAt = -1, chatNoMoneyAt = -1, chatListOkAt = -1, chatListLimitAt = -1;
    private static long noMoneyUntil, listLimitUntil;
    private static boolean listingBroken;
    private static final Map<String, Long> skipOffers = new HashMap<>();
    private static final Map<String, Long> notedOnce = new HashMap<>();

    /** Jedna wystawka: slot ekwipunku, przedmiot, ilość, cena całej wystawki. */
    static final class SellJob {
        final int inv;
        final MarketItem item;
        final int qty;
        final long price;

        SellJob(int inv, MarketItem item, int qty, long price) {
            this.inv = inv;
            this.item = item;
            this.qty = qty;
            this.price = price;
        }
    }

    private static final ArrayDeque<SellJob> sellQueue = new ArrayDeque<>();
    private static SellJob selling;
    private static int sellStep, savedSlot = -1, sellBaseCount, swappedFrom = -1;
    private static long sellAt;

    private static int jumpsDone, afkStep;
    private static long afkAt;

    /** Liczniki do zakładki "Auto rynek". */
    static final class Stats {
        long startedAt;
        int bought, listed, restarts, failed, afk;
        double spent, listedValue;
    }

    static final Stats stats = new Stats();
    private static final ArrayDeque<String> log = new ArrayDeque<>();

    // ---------- sterowanie ----------
    public static boolean enabled() {
        return on;
    }

    /** Czy pracuje (włączony i nie wstrzymany na analizę). */
    public static boolean running() {
        return on && !paused;
    }

    /** W trakcie zakupu, wystawiania albo skoków (wtedy skan autopilota czeka). */
    public static boolean busy() {
        return running() && (state == State.BUY_WAIT || state == State.CONFIRM || state == State.RESULT || state == State.SELL || state == State.AFK);
    }

    static AnalizaConfig cfg() {
        AnalizaConfig c = RynekAnalizaMod.config();
        return c != null ? c : new AnalizaConfig();
    }

    public static void toggle() {
        if (on) stop("klawisz");
        else start();
    }

    public static void start() {
        if (on) return;
        ItemStore st = BkBridge.store();
        if (st == null || st.items().isEmpty()) {
            say("Lista przedmiotów jest pusta - zaimportuj ją z BK albo dodaj przedmioty (panel, zakładka \"Auto rynek\").");
            return;
        }
        AnalizaConfig c = cfg();
        long now = clock.getAsLong();
        on = true;
        paused = false;
        cur = null;
        sellQueue.clear();
        selling = null;
        skipOffers.clear();
        notedOnce.clear();
        openFails = buyTimeouts = 0;
        sortConfirmed = sortGaveUp = false;
        listingBroken = false;
        noMoneyUntil = listLimitUntil = 0;
        sellDue = true;                 // wystawianie zaraz na starcie (przed otwarciem rynku)
        nextSellAt = now;
        nextAfkAt = now + c.amAfkMin * 60_000L;
        stats.startedAt = now;
        goHome(0);
        int buyable = 0;
        for (MarketItem it : st.items()) if (!Boolean.FALSE.equals(it.buyEnabled) && it.maxPrice > 0) buyable++;
        String mode = c.amBuy ? "snajper (" + buyable + " przedm. z ceną skupu), wystawianie " + (c.amSell ? "przy każdym restarcie rynku" : "WYŁ")
                : "TYLKO SPRZEDAŻ (wystawianie co " + resetSec() + " s, bez kupowania)";
        say("Start: " + (c.amAnalysis ? "najpierw analiza cen, potem " : "") + mode + ", anty-AFK " + (c.amAntiAfk ? "co " + c.amAfkMin + " min" : "WYŁ")
                + (c.amBuy ? ", restart rynku " + (c.amRestartSec > 0 ? "co " + c.amRestartSec + " s" : "WYŁ") : "")
                + (c.amAnalysis ? ", analiza co " + c.apIntervalMin + " min" : "") + ". Stop: " + keyName() + ".");
        if (!c.amAnalysis) {
            say("Uwaga: analiza jest wyłączona - ceny nie będą się same odświeżać (zakładka Auto rynek).");
            return;
        }
        boolean scan;
        try {
            scan = startScan.getAsBoolean();
        } catch (Throwable t) {
            scan = false;
        }
        if (!scan && on && !paused) note("Analiza na starcie nie ruszyła - zaczynam od razu.");
    }

    /** Stan "w gotowości": snajper - otwarcie rynku; tylko sprzedaż - czekanie na wystawianie. */
    private static void goHome(long delayMs) {
        setState(cfg().amBuy ? State.OPEN : State.IDLE, delayMs);
    }

    /** Co ile s restart rynku (snajper) albo wystawianie (tylko sprzedaż / restarty wyłączone). */
    static int resetSec() {
        int s = cfg().amRestartSec;
        return s > 0 ? s : 60;
    }

    /** Czy można teraz wystawiać (wystawianie włączone albo tryb tylko sprzedaż, nie ma limitu na rynku). */
    private static boolean canList(long now) {
        AnalizaConfig c = cfg();
        return (c.amSell || !c.amBuy) && !listingBroken && now >= listLimitUntil;
    }

    public static void stop(String why) {
        if (!on) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        releaseJump(mc);
        restoreSlot(mc);
        if (mc != null && mc.player != null && (state == State.CONFIRM || state == State.RESULT)
                && mc.currentScreen instanceof HandledScreen<?> hs && marketScreen(hs) == null) {
            mc.player.closeHandledScreen(); // nie zostawiamy otwartego okna potwierdzenia
        }
        on = false;
        paused = false;
        cur = null;
        sellQueue.clear();
        selling = null;
        state = State.OFF;
        if (MarketAnalyzer.isRunning() && MarketAnalyzer.isCycle()) MarketAnalyzer.stop(); // analiza auto rynku - też stop
        say("Stop" + (why == null || why.isBlank() ? "" : " (" + why + ")") + ". Kupiono: " + stats.bought + " za "
                + AnalizaScreen.fmt(stats.spent) + "$, wystawiono: " + stats.listed + ".");
    }

    /** Analiza zatrzymuje auto rynek na czas skanu (dawne "zatrzymaj BK"). */
    public static void pause() {
        if (!on || paused) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        releaseJump(mc);
        restoreSlot(mc);
        paused = true;
        cur = null;
        sellQueue.clear();
        selling = null;
        state = State.PAUSED;
        note("Wstrzymany na analizę.");
    }

    /** Po analizie: wraca na rynek. */
    public static void resume() {
        if (!on || !paused) return;
        paused = false;
        goHome(300);
        note("Wznowiony po analizie.");
    }

    /** Sprzedała się nasza wystawka (komunikat serwera): zwolniło się miejsce na rynku. */
    static void onSold() {
        if (listLimitUntil > 0) {
            listLimitUntil = 0;
            note("Sprzedaż - jest miejsce na rynku, wystawianie przy następnym restarcie.");
        }
    }

    private static void setState(State s, long delayMs) {
        long now = clock.getAsLong();
        state = s;
        stateAt = now;
        waitUntil = now + Math.max(0, delayMs);
    }

    // ---------- komunikaty serwera ----------
    /** Każdy komunikat z czatu serwera (zdarzenie GAME). */
    public static void onChat(String raw) {
        if (raw == null || raw.isEmpty()) return;
        String p = ItemMatcher.plainChat(raw);
        if (p.startsWith("[auto rynek]") || p.startsWith("[analiza]")) return;
        long now = clock.getAsLong();
        if (ItemMatcher.any(p, ItemMatcher.NO_MONEY)) chatNoMoneyAt = now;
        else if (ItemMatcher.any(p, ItemMatcher.BUY_FAIL)) chatFailAt = now;
        else if (ItemMatcher.any(p, ItemMatcher.BUY_OK)) chatOkAt = now;
        if (ItemMatcher.any(p, ItemMatcher.LIST_LIMIT)) chatListLimitAt = now;
        else if (ItemMatcher.any(p, ItemMatcher.LIST_OK)) chatListOkAt = now;
    }

    // ---------- główna pętla ----------
    public static void tick(MinecraftClient mc) {
        if (!on) return;
        long now = clock.getAsLong();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            stop("rozłączono");
            return;
        }
        if (paused) return;
        if (MarketAnalyzer.isRunning()) return; // analiza ma rynek dla siebie
        try {
            step(mc, now);
        } catch (Throwable t) {
            note("Błąd: " + t);
            t.printStackTrace();
            restart(mc, "błąd (" + t.getClass().getSimpleName() + ")", now);
        }
    }

    private static void step(MinecraftClient mc, long now) {
        Screen scr = mc.currentScreen;
        HandledScreen<?> market = marketScreen(scr);
        boolean loading = scr instanceof HandledScreen<?> hs0 && MarketAnalyzer.isMarket(hs0); // okno rynku bez zawartości (jeszcze się wczytuje)
        // tylko sprzedaż: rynek otworzył gracz (bot go nie używa) - też "inne okno"
        boolean sellOnlyScreen = !cfg().amBuy && (state == State.IDLE || state == State.HOLD);
        boolean users = scr != null && ((market == null && !loading) || sellOnlyScreen);
        if (users && !expectsOtherScreen(scr)) {
            if (state != State.HOLD) {
                releaseJump(mc);
                if (state == State.SELL) restoreSlot(mc);
                note("Czekam - otwarte inne okno.");
                setState(State.HOLD, 0);
            }
            return;
        }
        if (state == State.HOLD) {
            if (cur != null && chatOkAt >= cur.clickAt) bought(cur, now, "czat");
            cur = null;
            sellQueue.clear();
            selling = null;
            goHome(market != null ? 0 : 500);
            return;
        }
        if (now < waitUntil) return;
        switch (state) {
            case OPEN -> open(mc, market, now);
            case WAIT_OPEN -> waitOpen(mc, market, now);
            case SORT -> sort(mc, market, now);
            case SORT_WAIT -> {
                if (market == null) setState(State.OPEN, cfg().amRestartPauseMs);
                else if (MarketAnalyzer.signature(market) != sortBaseSig || now - stateAt > 1500) setState(State.SORT, 100);
            }
            case SNIPE -> snipe(mc, market, now);
            case BUY_WAIT -> buyWait(mc, scr, market, now);
            case CONFIRM -> confirm(mc, scr, market, now);
            case RESULT -> result(mc, scr, market, now);
            case SELL -> sell(mc, scr, now);
            case AFK -> afk(mc, now);
            case RESTART -> goHome(0);
            case IDLE -> idle(mc, now);
            default -> {}
        }
    }

    /** W trakcie zakupu okno inne niż rynek to okno potwierdzenia; po "/wystaw" - okno, którego nie umiemy obsłużyć. */
    private static boolean expectsOtherScreen(Screen scr) {
        if (!(scr instanceof HandledScreen<?>)) return false;
        return state == State.BUY_WAIT || state == State.CONFIRM || state == State.RESULT || state == State.SELL;
    }

    static HandledScreen<?> marketScreen(Screen s) {
        if (!(s instanceof HandledScreen<?> hs)) return null;
        return MarketAnalyzer.isMarket(hs) && MarketAnalyzer.ready(hs) ? hs : null;
    }

    private static void open(MinecraftClient mc, HandledScreen<?> market, long now) {
        if (!cfg().amBuy) { // tryb tylko sprzedaż - rynek niepotrzebny
            if (market != null) mc.player.closeHandledScreen();
            setState(State.IDLE, 0);
            return;
        }
        if (market != null) {
            marketOpened(market, now);
            return;
        }
        if (mc.currentScreen != null) { // niewczytane okno rynku - po 5 s zamknąć i otworzyć od nowa
            if (now - stateAt > 5000) {
                mc.player.closeHandledScreen();
                setState(State.OPEN, cfg().amRestartPauseMs);
            }
            return;
        }
        if (sellDue) { // start albo restart: najpierw wystawianie, potem rynek
            sellDue = false;
            if (canList(now) && startSell(mc, now, null)) return;
        }
        mc.player.networkHandler.sendChatCommand(cfg().amCommand);
        setState(State.WAIT_OPEN, 0);
    }

    private static void waitOpen(MinecraftClient mc, HandledScreen<?> market, long now) {
        if (market != null) {
            if (now - stateAt >= 100) marketOpened(market, now);
            return;
        }
        if (now - stateAt <= 5000) return;
        openFails++;
        if (openFails >= 5) {
            stop("rynek nie otworzył się 5 razy z rzędu - sprawdź komendę rynku w ustawieniach");
            return;
        }
        restart(mc, "Nie udało się otworzyć rynku - restart (" + openFails + "/5)", now);
    }

    private static void marketOpened(HandledScreen<?> market, long now) {
        openFails = 0;
        sessionAt = now;
        lastChangeAt = now;
        lastRefreshAt = now;
        lastSig = MarketAnalyzer.signature(market);
        scanNeeded = true;
        sortClicks = 0;
        setState(sortGaveUp ? State.SNIPE : State.SORT, 0);
    }

    private static int sortSlot(ScreenHandler h) {
        int size = MarketAnalyzer.containerSize(h);
        int s = cfg().amSortSlot;
        return s >= 0 && s < size ? s : size - 1;
    }

    private static void sort(MinecraftClient mc, HandledScreen<?> market, long now) {
        if (market == null) {
            setState(State.OPEN, cfg().amRestartPauseMs);
            return;
        }
        ScreenHandler h = market.getScreenHandler();
        int slot = sortSlot(h);
        if (ItemMatcher.sortSelected(h.slots.get(slot).getStack(), cfg().amSortKeyword)) {
            if (!sortConfirmed) note("Sortowanie: Najnowsze.");
            sortConfirmed = true;
            startSnipe(market, now);
            return;
        }
        if (sortClicks >= 6) {
            say("Nie widzę zaznaczonej opcji sortowania \"" + cfg().amSortKeyword + "\" - snajpię przy obecnym sortowaniu.");
            sortGaveUp = true;
            startSnipe(market, now);
            return;
        }
        click(mc, market, slot, 1); // PPM = następna opcja sortowania
        sortClicks++;
        sortBaseSig = MarketAnalyzer.signature(market);
        setState(State.SORT_WAIT, 0);
    }

    private static void startSnipe(HandledScreen<?> market, long now) {
        lastSig = MarketAnalyzer.signature(market);
        lastChangeAt = now;
        scanNeeded = true;
        changedSinceRefresh = true; // świeże okno - następne odświeżenie po zwykłym odstępie
        setState(State.SNIPE, 0);
    }

    // ---------- snajper ----------
    private static void snipe(MinecraftClient mc, HandledScreen<?> market, long now) {
        AnalizaConfig c = cfg();
        if (market == null) {
            boolean loading = mc.currentScreen instanceof HandledScreen<?> hs && MarketAnalyzer.isMarket(hs);
            if (loading && now - lastChangeAt < c.amStuckSec * 1000L) return; // okno rynku przeładowuje się
            note(loading ? "Okno rynku się nie wczytało - otwieram ponownie." : "Rynek zamknięty - otwieram ponownie.");
            if (loading) mc.player.closeHandledScreen();
            setState(State.OPEN, c.amRestartPauseMs);
            return;
        }
        int sig = MarketAnalyzer.signature(market);
        if (sig != lastSig) {
            lastSig = sig;
            lastChangeAt = now;
            changedSinceRefresh = true;
            scanNeeded = true;
        }
        if (!c.amBuy) { // przełączono na "Tylko sprzedaż"
            mc.player.closeHandledScreen();
            note("Tryb: tylko sprzedaż - zamykam rynek.");
            setState(State.IDLE, 300);
            return;
        }
        PlayerInventory inv = mc.player.getInventory();
        boolean full = inv.getEmptySlot() < 0;
        if (full) {
            if (canList(now) && startSell(mc, now, "pełny ekwipunek")) return;
            if (!fullWarned) say("Pełny ekwipunek - nie kupuję, dopóki nie zrobi się miejsce.");
            fullWarned = true;
        } else {
            fullWarned = false;
        }
        if (c.amAntiAfk && now >= nextAfkAt) {
            startAfk(mc, now);
            return;
        }
        if (c.amRestartSec > 0 && now - sessionAt >= c.amRestartSec * 1000L) {
            // restart rynku = przy okazji wystawianie (rynek i tak jest zamykany); bez przerwy, gdy jest co wystawiać
            if (canList(now) && startSell(mc, now, "restart rynku")) {
                stats.restarts++;
                return;
            }
            restart(mc, "Restart rynku (co " + c.amRestartSec + " s).", now);
            return;
        }
        if (c.amRestartSec <= 0 && now >= nextSellAt) { // restarty wyłączone - wystawianie co minutę
            nextSellAt = now + 60_000L;
            if (canList(now) && startSell(mc, now, null)) return;
        }
        if (now - lastChangeAt >= c.amStuckSec * 1000L) {
            restart(mc, "Rynek bez zmian przez " + c.amStuckSec + " s - restart.", now);
            return;
        }
        if (scanNeeded && !full) {
            scanNeeded = false;
            Attempt a = findOffer(mc, market, now);
            if (a != null) {
                buy(mc, market, a, now);
                return;
            }
        }
        if (now - lastRefreshAt >= c.amRefreshMs && (changedSinceRefresh || now - lastRefreshAt >= 2000)) {
            int slot = c.amRefreshSlot < MarketAnalyzer.containerSize(market.getScreenHandler()) ? c.amRefreshSlot : 0;
            click(mc, market, slot, 1); // PPM = odśwież
            lastRefreshAt = now;
            changedSinceRefresh = false;
        }
    }

    /** Przedmioty do kupna pogrupowane po id (pusty klucz = dowolne id). */
    private static Map<String, List<MarketItem>> buyables() {
        Map<String, List<MarketItem>> out = new LinkedHashMap<>();
        ItemStore st = BkBridge.store();
        if (st == null) return out;
        for (MarketItem it : st.items()) {
            if (Boolean.FALSE.equals(it.buyEnabled) || !(it.maxPrice > 0)) continue; // skup 0 = nigdy (BK kupował wtedy wszystko)
            out.computeIfAbsent(ItemMatcher.material(it), k -> new ArrayList<>()).add(it);
        }
        return out;
    }

    /**
     * Wartość oferty do porównania ze skupem (jak w BK): tryb "Unit" - cena za sztukę, gdy ofert ma co najmniej "ilość
     * kupna" sztuk; tryb "Sum" - cena całej oferty, gdy ma dokładnie tyle sztuk. NaN = oferta nie spełnia warunku ilości.
     */
    static double compareValue(MarketItem it, double total, int count, boolean globalUnit) {
        boolean unit = Boolean.TRUE.equals(it.unitMode) || globalUnit;
        int min = it.buyCount();
        if (unit ? count < min : count != min) return Double.NaN;
        return unit ? total / Math.max(1, count) : total;
    }

    /** Pierwsza oferta w oknie (od lewego górnego rogu = najnowsze), którą warto kupić; null = brak. */
    static Attempt findOffer(MinecraftClient mc, HandledScreen<?> market, long now) {
        AnalizaConfig c = cfg();
        if (!c.amBuy || now < noMoneyUntil) return null;
        Map<String, List<MarketItem>> byId = buyables();
        if (byId.isEmpty()) return null;
        skipOffers.values().removeIf(t -> t < now);
        ScreenHandler h = market.getScreenHandler();
        int end = Math.max(0, MarketAnalyzer.containerSize(h) - 9);
        double balance = -2;
        for (int i = 0; i < end; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (st.isEmpty()) continue;
            List<MarketItem> cands = new ArrayList<>(byId.getOrDefault(ItemMatcher.itemId(st), List.of()));
            cands.addAll(byId.getOrDefault("", List.of()));
            if (cands.isEmpty()) continue;
            double total = ItemMatcher.offerPrice(st);
            if (!(total > 0)) continue;
            String id = MarketObserver.identity(st, total);
            if (skipOffers.containsKey(id)) continue;
            if (MarketAnalyzer.isOwn(st)) continue;
            MarketItem best = null;
            double bestCmp = 0;
            for (MarketItem it : cands) {
                double cmp = compareValue(it, total, st.getCount(), c.unitModeGlobal);
                if (Double.isNaN(cmp) || cmp > it.maxPrice) continue;
                if (!ItemMatcher.matches(st, it) || !ItemMatcher.meetsMinDays(it, st)) continue;
                if (best == null || it.maxPrice < best.maxPrice) {
                    best = it;
                    bestCmp = cmp;
                }
            }
            if (best == null || !(bestCmp < best.maxPrice)) continue;
            if (balance == -2) balance = BkBridge.walletBalance();
            if (balance > 0 && total > balance) {
                noteOnce("money:" + id, "Za mało pieniędzy na " + ItemMatcher.stripColors(st.getName().getString()) + " za " + AnalizaScreen.fmt(total)
                        + "$ (saldo " + AnalizaScreen.fmt(balance) + "$).", now);
                continue;
            }
            Attempt a = new Attempt();
            a.item = best;
            a.stack = st.copy();
            a.name = ItemMatcher.stripColors(st.getName().getString());
            a.total = total;
            a.limit = best.maxPrice;
            a.count = Math.max(1, st.getCount());
            a.slot = i;
            a.id = id;
            return a;
        }
        return null;
    }

    private static void buy(MinecraftClient mc, HandledScreen<?> market, Attempt a, long now) {
        a.baseInv = countMatching(mc.player.getInventory(), a.item);
        a.clickAt = now;
        cur = a;
        click(mc, market, a.slot, 0); // LPM = kup
        note("Kupuję: " + a.name + (a.count > 1 ? " x" + a.count : "") + " za " + AnalizaScreen.fmt(a.total) + "$ (skup " + AnalizaScreen.fmt(a.limit) + ").");
        setState(State.BUY_WAIT, 0);
    }

    private static void buyWait(MinecraftClient mc, Screen scr, HandledScreen<?> market, long now) {
        Attempt a = cur;
        if (a == null) {
            setState(State.SNIPE, 0);
            return;
        }
        if (scr instanceof HandledScreen<?> && market == null) { // okno potwierdzenia - klikamy od razu, bez czekania na tick
            setState(State.CONFIRM, 0);
            confirm(mc, scr, null, now);
            return;
        }
        if (chatOkAt >= a.clickAt || chatFailAt >= a.clickAt || chatNoMoneyAt >= a.clickAt) { // zakup bez potwierdzenia
            setState(State.RESULT, 0);
            return;
        }
        if (market != null && !a.reclicked && now - a.clickAt >= 700) {
            ItemStack same = market.getScreenHandler().slots.get(a.slot).getStack();
            if (!same.isEmpty() && MarketObserver.identity(same, ItemMatcher.offerPrice(same)).equals(a.id)) {
                click(mc, market, a.slot, 0);
                a.reclicked = true;
            }
        }
        if (now - a.clickAt < 3000) return;
        if (countMatching(mc.player.getInventory(), a.item) >= a.baseInv + a.count) {
            bought(a, now, "ekwipunek");
            afterAttempt(mc, now);
            return;
        }
        failed(a, "brak odpowiedzi serwera", now);
        if (++buyTimeouts >= 3) {
            buyTimeouts = 0;
            restart(mc, "Timeout zakupu - restart.", now);
            return;
        }
        afterAttempt(mc, now);
    }

    private static void confirm(MinecraftClient mc, Screen scr, HandledScreen<?> market, long now) {
        Attempt a = cur;
        if (a == null) {
            afterAttempt(mc, now);
            return;
        }
        if (!(scr instanceof HandledScreen<?> hs) || market != null) { // okno zniknęło albo wrócił rynek
            setState(State.RESULT, 0);
            return;
        }
        ScreenHandler h = hs.getScreenHandler();
        int size = Math.max(0, MarketAnalyzer.containerSize(h));
        boolean any = false;
        for (int i = 0; i < size; i++) if (!h.slots.get(i).getStack().isEmpty()) { any = true; break; }
        if (!any && now - stateAt < 1000) return; // zawartość okna jeszcze nie doszła
        // kompletne okno (przycisk znaleziony po nazwie/barwniku i kupowany przedmiot) = klik od razu; niepełne - chwila
        // na resztę zawartości (serwer może dosyłać przedmioty pojedynczo)
        if (now - stateAt < 250 && !(confirmFound(h, size, a) && sameItemShown(h, size, a))) return;
        String bad = verifyConfirm(h, size, a);
        if (bad != null) {
            say("Anuluję zakup " + a.name + ": " + bad + ".");
            mc.player.closeHandledScreen();
            failed(a, bad, now);
            cur = null;
            setState(State.OPEN, 300);
            return;
        }
        int btn = confirmButton(h, size, a);
        if (btn < 0) {
            say("Nie widzę przycisku potwierdzenia zakupu - zamykam okno (ustaw slot/nazwę przycisku w ustawieniach).");
            mc.player.closeHandledScreen();
            failed(a, "brak przycisku potwierdzenia", now);
            cur = null;
            setState(State.OPEN, 300);
            return;
        }
        click(mc, hs, btn, 0);
        a.confirmClicks = 1;
        a.confirmAt = now;
        setState(State.RESULT, 0);
    }

    /** Czy w oknie potwierdzenia jest ten sam przedmiot i nie drożej; null = w porządku, inaczej powód. */
    static String verifyConfirm(ScreenHandler h, int size, Attempt a) {
        String wantId = ItemMatcher.itemId(a.stack);
        boolean sameId = false;
        for (int i = 0; i < size; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (st.isEmpty() || !ItemMatcher.itemId(st).equals(wantId)) continue;
            sameId = true;
            if (!ItemMatcher.stripColors(st.getName().getString()).equalsIgnoreCase(a.name)) continue;
            double p = ItemMatcher.offerPrice(st);
            if (p > 0 && p > a.total + Math.max(0.01, a.total * 1e-4)) {
                return "w oknie potwierdzenia cena " + AnalizaScreen.fmt(p) + "$ zamiast " + AnalizaScreen.fmt(a.total) + "$";
            }
            return null;
        }
        return sameId ? "w oknie potwierdzenia jest inny przedmiot" : null;
    }

    private static final String[] CONFIRM_WORDS = {"potwierdz", "kup", "confirm", "akceptuj", "buy", "tak"};
    private static final String[] CANCEL_WORDS = {"anuluj", "cancel", "odrzuc", "wroc", "powrot", "zamknij", "rezygn"};

    /**
     * Przycisk "Potwierdź": po nazwie z ustawień, po słowach w nazwie albo zielony barwnik (pomijając sam przedmiot
     * i przyciski anulowania), zapasowo slot z ustawień.
     */
    static int confirmButton(ScreenHandler h, int size, Attempt a) {
        int i = namedConfirm(h, size, a);
        if (i >= 0) return i;
        int slot = cfg().amConfirmSlot;
        return slot >= 0 && slot < size && !h.slots.get(slot).getStack().isEmpty() ? slot : -1;
    }

    /** Przycisk potwierdzenia rozpoznany po nazwie albo barwniku (bez zapasowego slotu); -1 = nie ma (jeszcze). */
    private static int namedConfirm(ScreenHandler h, int size, Attempt a) {
        AnalizaConfig c = cfg();
        String want = BkChatGuard.plain(c.amConfirmName.trim().toLowerCase(Locale.ROOT));
        String wantId = a == null ? "" : ItemMatcher.itemId(a.stack);
        int dye = -1;
        for (int i = 0; i < size; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (st.isEmpty()) continue;
            String name = ItemMatcher.plainChat(st.getName().getString());
            if (!want.isEmpty()) {
                if (name.contains(want)) return i;
                continue;
            }
            String id = ItemMatcher.itemId(st);
            if (id.equals(wantId) || sellerLore(st)) continue; // sam przedmiot
            if (ItemMatcher.any(name, CANCEL_WORDS) || name.matches("(?s).*\\bnie\\b.*")) continue;
            if (ItemMatcher.any(name, CONFIRM_WORDS)) return i;
            if (dye < 0 && (id.equals("lime_dye") || id.equals("green_dye"))) dye = i; // zielony barwnik = potwierdzenie
        }
        return dye;
    }

    private static boolean confirmFound(ScreenHandler h, int size, Attempt a) {
        return namedConfirm(h, size, a) >= 0;
    }

    /** Czy w oknie potwierdzenia jest już przedmiot o id kupowanej oferty. */
    private static boolean sameItemShown(ScreenHandler h, int size, Attempt a) {
        String wantId = ItemMatcher.itemId(a.stack);
        for (int i = 0; i < size; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (!st.isEmpty() && ItemMatcher.itemId(st).equals(wantId)) return true;
        }
        return false;
    }

    /** Ponowne kliknięcie potwierdzenia, gdy serwer nie odpowiedział: nie wcześniej niż po 4 x ping (min. 600 ms). */
    static long reclickMs() {
        return Math.max(600L, Math.min(3000L, 4L * PingTiming.estimateMs()));
    }

    private static boolean sellerLore(ItemStack st) {
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return false;
        for (Text t : lore.lines()) {
            String l = ItemMatcher.plainChat(t.getString());
            if (l.contains("wystawi") || l.contains("sprzedawca") || l.contains("seller")) return true;
        }
        return false;
    }

    private static void result(MinecraftClient mc, Screen scr, HandledScreen<?> market, long now) {
        Attempt a = cur;
        if (a == null) {
            afterAttempt(mc, now);
            return;
        }
        long since = a.confirmAt > 0 ? a.confirmAt : a.clickAt;
        if (chatNoMoneyAt >= since) {
            noMoneyUntil = now + 60_000L;
            say("Brak środków na " + a.name + " - kupowanie wstrzymane na minutę.");
            failed(a, "brak środków", now);
            afterAttempt(mc, now);
            return;
        }
        // udany zakup przed "już sprzedany" (ponowne kliknięcie po udanym zakupie dostaje taki komunikat)
        boolean invUp = now - since >= 100 && countMatching(mc.player.getInventory(), a.item) >= a.baseInv + a.count;
        if (chatOkAt >= since || invUp) {
            bought(a, now, chatOkAt >= since ? "czat" : "ekwipunek");
            afterAttempt(mc, now);
            return;
        }
        if (chatFailAt >= since) {
            failed(a, "ktoś był szybszy", now);
            afterAttempt(mc, now);
            return;
        }
        boolean confirmOpen = scr instanceof HandledScreen<?> && market == null;
        if (confirmOpen && a.confirmClicks > 0 && a.confirmClicks < 3 && now - a.confirmAt >= reclickMs()) {
            HandledScreen<?> hs = (HandledScreen<?>) scr;
            ScreenHandler h = hs.getScreenHandler();
            int btn = confirmButton(h, MarketAnalyzer.containerSize(h), a);
            if (btn >= 0) {
                click(mc, hs, btn, 0);
                a.confirmClicks++;
                a.confirmAt = now;
                return;
            }
        }
        if (now - since < 4000) return;
        if (confirmOpen) {
            failed(a, "zacięte okno potwierdzenia", now);
            restart(mc, "Zacięcie na ekranie potwierdzenia - restart.", now);
            return;
        }
        failed(a, "brak potwierdzenia zakupu", now);
        afterAttempt(mc, now);
    }

    /** Po próbie zakupu: zamknij okno potwierdzenia, jeśli zostało, i wróć do snajpienia. */
    private static void afterAttempt(MinecraftClient mc, long now) {
        cur = null;
        Screen scr = mc.currentScreen;
        HandledScreen<?> market = marketScreen(scr);
        if (scr instanceof HandledScreen<?> && market == null) {
            mc.player.closeHandledScreen();
            setState(State.OPEN, 300);
            return;
        }
        if (market != null) {
            startSnipe(market, now);
            return;
        }
        goHome(300);
    }

    private static void bought(Attempt a, long now, String how) {
        buyTimeouts = 0;
        skipOffers.put(a.id, now + 60_000L);
        BkBridge.recordPurchase(a.stack, a.total, a.count);
        a.item.toSell += a.count;
        ItemStore st = BkBridge.store();
        if (st != null) st.save();
        stats.bought++;
        stats.spent += a.total;
        say("Kupiono: " + a.name + (a.count > 1 ? " x" + a.count : "") + " za " + AnalizaScreen.fmt(a.total) + "$ (skup "
                + AnalizaScreen.fmt(a.limit) + (a.count > 1 && a.limit < a.total ? "$/szt." : "$") + ").");
    }

    private static void failed(Attempt a, String why, long now) {
        stats.failed++;
        skipOffers.put(a.id, now + 30_000L);
        note("Nie kupiono " + a.name + ": " + why + ".");
    }

    // ---------- wystawianie ----------
    /** Wystawki z ekwipunku: kupione przez snajpera (toSell) albo wszystkie z "Wystawiaj"; cena z listy. */
    static List<SellJob> sellJobs(PlayerInventory inv) {
        List<SellJob> out = new ArrayList<>();
        ItemStore st = BkBridge.store();
        if (st == null) return out;
        Map<MarketItem, Integer> left = new HashMap<>();
        java.util.Set<MarketItem> found = new java.util.HashSet<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            for (MarketItem it : st.items()) {
                if (!(it.sellPrice > 0)) continue;
                if (!ItemMatcher.matches(s, it)) continue;
                found.add(it);
                boolean all = Boolean.TRUE.equals(it.sellEnabled);
                int allowed = all ? Integer.MAX_VALUE : left.getOrDefault(it, it.toSell);
                if (allowed <= 0) break;
                int units = it.sellUnits();
                int qty = Math.min(s.getCount(), Math.min(units, allowed));
                long price = Math.round(it.sellPrice / units * qty);
                if (qty < 1 || price < 1) break;
                out.add(new SellJob(i, it, qty, price));
                if (!all) left.put(it, allowed - qty);
                break;
            }
        }
        // kupione wcześniej, ale już nie ma ich w ekwipunku (zużyte, sprzedane ręcznie) - licznik do zera
        boolean changed = false;
        for (MarketItem it : st.items()) {
            if (it.toSell > 0 && !found.contains(it)) {
                it.toSell = 0;
                changed = true;
            }
        }
        if (changed) st.touch();
        return out;
    }

    private static boolean startSell(MinecraftClient mc, long now, String why) {
        PlayerInventory inv = mc.player.getInventory();
        List<SellJob> jobs = sellJobs(inv);
        if (jobs.isEmpty()) return false;
        sellQueue.clear();
        sellQueue.addAll(jobs);
        selling = null;
        sellStep = 0;
        savedSlot = inv.selectedSlot;
        if (mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
        note("Wystawiam " + jobs.size() + " przedm." + (why == null ? "" : " (" + why + ")") + ".");
        setState(State.SELL, 400);
        return true;
    }

    private static void sell(MinecraftClient mc, Screen scr, long now) {
        AnalizaConfig c = cfg();
        PlayerInventory inv = mc.player.getInventory();
        if (scr instanceof HandledScreen<?>) {
            if (marketScreen(scr) != null) { // spóźnione okno rynku - zamknąć i wystawiać dalej
                mc.player.closeHandledScreen();
                return;
            }
            // po "/wystaw" otworzyło się okno - tego nie umiemy potwierdzić
            say("Po wystawieniu otworzyło się okno, którego nie umiem obsłużyć - zamykam je i wyłączam wystawianie do restartu auto rynku.");
            mc.player.closeHandledScreen();
            listingBroken = true;
            finishSell(mc, now);
            return;
        }
        if (selling == null) {
            if (now < sellAt + 600 && sellStep != 0) return; // odstęp między wystawkami
            selling = sellQueue.poll();
            if (selling == null) {
                finishSell(mc, now);
                return;
            }
            ItemStack s = inv.getStack(selling.inv);
            if (s.isEmpty() || s.getCount() < selling.qty || !ItemMatcher.matches(s, selling.item)) {
                selling = null;
                sellStep = 3;
                return;
            }
            if (selling.inv < 9) {
                inv.selectedSlot = selling.inv;
            } else {
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, selling.inv, inv.selectedSlot, SlotActionType.SWAP, mc.player);
                swappedFrom = selling.inv;
            }
            sellAt = now;
            sellStep = 1;
            return;
        }
        if (sellStep == 1) {
            if (now - sellAt < 350) return;
            ItemStack hand = inv.getMainHandStack();
            if (hand.isEmpty() || hand.getCount() < selling.qty || !ItemMatcher.matches(hand, selling.item)) {
                note("Nie trzymam przedmiotu do wystawienia (" + ItemMatcher.stripColors(selling.item.name) + ") - pomijam.");
                swapBack(mc);
                selling = null;
                sellStep = 3;
                return;
            }
            sellBaseCount = countMatching(inv, selling.item);
            String cmd = c.amSellCommand.replace("{quantity}", String.valueOf(selling.qty)).replace("{price}", String.valueOf(selling.price));
            mc.player.networkHandler.sendChatCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
            sellAt = now;
            sellStep = 2;
            return;
        }
        if (sellStep == 2) {
            if (chatListLimitAt >= sellAt) {
                say("Brak miejsca na rynku (limit wystawionych) - " + (cfg().amBuy ? "snajpię dalej; " : "")
                        + "wystawię znowu po sprzedaży albo za 5 min.");
                listLimitUntil = now + 5 * 60_000L;
                sellQueue.clear();
                selling = null;
                finishSell(mc, now);
                return;
            }
            boolean gone = countMatching(inv, selling.item) <= sellBaseCount - selling.qty;
            if (chatListOkAt >= sellAt || gone) {
                MarketItem it = selling.item;
                it.toSell = Math.max(0, it.toSell - selling.qty);
                stats.listed++;
                stats.listedValue += selling.price;
                say("Wystawiono: " + ItemMatcher.stripColors(it.name == null || it.name.isBlank() ? it.material : it.name)
                        + (selling.qty > 1 ? " x" + selling.qty : "") + " za " + selling.price + "$.");
                ItemStore st = BkBridge.store();
                if (st != null) st.save();
                swapBack(mc);
                selling = null;
                sellStep = 3;
                sellAt = now;
                return;
            }
            if (now - sellAt > 3000) {
                note("Brak potwierdzenia wystawienia " + ItemMatcher.stripColors(selling.item.name) + ".");
                swapBack(mc);
                selling = null;
                sellStep = 3;
                sellAt = now;
            }
        }
    }

    /** Przedmiot z plecaka był zamieniony z wybranym slotem paska - zamiana z powrotem (pasek gracza jak przed wystawianiem). */
    private static void swapBack(MinecraftClient mc) {
        if (swappedFrom < 0 || mc.player == null || mc.interactionManager == null) return;
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, swappedFrom, mc.player.getInventory().selectedSlot, SlotActionType.SWAP, mc.player);
        swappedFrom = -1;
    }

    private static void finishSell(MinecraftClient mc, long now) {
        restoreSlot(mc);
        sellQueue.clear();
        selling = null;
        nextSellAt = now + resetSec() * 1000L;
        goHome(300);
    }

    // ---------- tryb "Tylko sprzedaż" ----------
    private static void idle(MinecraftClient mc, long now) {
        AnalizaConfig c = cfg();
        if (c.amBuy) { // przełączono z powrotem na snajpera
            setState(State.OPEN, 0);
            return;
        }
        if (c.amAntiAfk && now >= nextAfkAt) {
            startAfk(mc, now);
            return;
        }
        if (now >= nextSellAt) {
            nextSellAt = now + resetSec() * 1000L;
            if (canList(now)) startSell(mc, now, null);
        }
    }

    private static void restoreSlot(MinecraftClient mc) {
        swapBack(mc);
        if (savedSlot >= 0 && savedSlot < 9 && mc != null && mc.player != null) mc.player.getInventory().selectedSlot = savedSlot;
        savedSlot = -1;
    }

    // ---------- anty-AFK ----------
    private static void startAfk(MinecraftClient mc, long now) {
        if (mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
        jumpsDone = 0;
        afkStep = 0;
        note("Anty-AFK: " + cfg().amAfkJumps + " skoki.");
        setState(State.AFK, 400);
    }

    private static void afk(MinecraftClient mc, long now) {
        AnalizaConfig c = cfg();
        if (afkStep == 0) {
            if (!mc.player.isOnGround() && now - stateAt < 3000) return;
            mc.options.jumpKey.setPressed(true);
            afkAt = now;
            afkStep = 1;
            return;
        }
        if (afkStep == 1) {
            if (now - afkAt < 100) return;
            mc.options.jumpKey.setPressed(false);
            jumpsDone++;
            afkAt = now;
            afkStep = 2;
            return;
        }
        if (now - afkAt < 500 || (!mc.player.isOnGround() && now - afkAt < 3000)) return;
        if (jumpsDone < Math.max(1, c.amAfkJumps)) {
            afkStep = 0;
            return;
        }
        stats.afk++;
        nextAfkAt = now + c.amAfkMin * 60_000L;
        goHome(300);
    }

    private static void releaseJump(MinecraftClient mc) {
        if (state == State.AFK && mc != null && mc.options != null && mc.options.jumpKey != null) mc.options.jumpKey.setPressed(false);
    }

    // ---------- restart ----------
    private static void restart(MinecraftClient mc, String why, long now) {
        stats.restarts++;
        sellDue = true; // po restarcie najpierw wystawianie
        note(why);
        releaseJump(mc);
        cur = null;
        if (mc.player != null && mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
        setState(State.RESTART, cfg().amRestartPauseMs);
    }

    // ---------- reakcja w każdej klatce ----------
    /**
     * Wywoływane w każdej klatce otwartego okna (nie tylko co tick gry = 50 ms): okno potwierdzenia jest klikane
     * w klatce, w której przyszła jego zawartość, a nowe oferty sprawdzane zaraz po odświeżeniu listy.
     */
    public static void onFrame(MinecraftClient mc) {
        if (!on || paused || mc == null || mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (MarketAnalyzer.isRunning()) return;
        long now = clock.getAsLong();
        if (now < waitUntil) return;
        try {
            Screen scr = mc.currentScreen;
            HandledScreen<?> market = marketScreen(scr);
            switch (state) {
                case BUY_WAIT -> buyWait(mc, scr, market, now);
                case CONFIRM -> confirm(mc, scr, market, now);
                case SNIPE -> {
                    if (market != null && cfg().amBuy) quickSnipe(mc, market, now);
                }
                default -> {}
            }
        } catch (Throwable t) {
            note("Błąd: " + t);
            t.printStackTrace();
        }
    }

    /** Nowa zawartość listy ofert: od razu szukamy oferty do kupienia (resztę - odświeżanie, restarty - robi snipe co tick). */
    private static void quickSnipe(MinecraftClient mc, HandledScreen<?> market, long now) {
        int sig = MarketAnalyzer.signature(market);
        if (sig == lastSig) return;
        lastSig = sig;
        lastChangeAt = now;
        changedSinceRefresh = true;
        if (mc.player.getInventory().getEmptySlot() < 0) { // pełny ekwipunek - decyduje snipe()
            scanNeeded = true;
            return;
        }
        scanNeeded = false;
        Attempt a = findOffer(mc, market, now);
        if (a != null) buy(mc, market, a, now);
    }

    // ---------- podświetlanie ofert ----------
    /** Oferta z listy w oknie rynku: slot, czy zysk po sprzedaży >= progu (zielona), zysk ($). */
    record Highlight(int slot, boolean good, double profit) {}

    private static int hlSig;
    private static List<Highlight> hl = List.of();

    /** Podświetlenie ofert przy snajpieniu (liczone raz na zmianę okna). */
    static List<Highlight> highlights(HandledScreen<?> market) {
        int sig = MarketAnalyzer.signature(market) * 31 + (int) Math.round(cfg().amHighlightProfit);
        if (sig == hlSig && hlSig != 0) return hl;
        hlSig = sig;
        try {
            hl = computeHighlights(market);
        } catch (Throwable t) {
            hl = List.of();
        }
        return hl;
    }

    static List<Highlight> computeHighlights(HandledScreen<?> market) {
        AnalizaConfig c = cfg();
        ItemStore store = BkBridge.store();
        if (store == null) return List.of();
        Map<String, List<MarketItem>> byId = new LinkedHashMap<>();
        for (MarketItem it : store.items()) {
            if (it.sellPrice > 0) byId.computeIfAbsent(ItemMatcher.material(it), k -> new ArrayList<>()).add(it);
        }
        if (byId.isEmpty()) return List.of();
        ScreenHandler h = market.getScreenHandler();
        int end = Math.max(0, MarketAnalyzer.containerSize(h) - 9);
        List<Highlight> out = new ArrayList<>();
        for (int i = 0; i < end; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (st.isEmpty()) continue;
            List<MarketItem> cands = new ArrayList<>(byId.getOrDefault(ItemMatcher.itemId(st), List.of()));
            cands.addAll(byId.getOrDefault("", List.of()));
            if (cands.isEmpty()) continue;
            double total = ItemMatcher.offerPrice(st);
            if (!(total > 0) || MarketAnalyzer.isOwn(st)) continue;
            MarketItem best = null;
            for (MarketItem it : cands) {
                if (!ItemMatcher.matches(st, it) || !ItemMatcher.meetsMinDays(it, st)) continue;
                // jak snajper: najniższy skup wśród pasujących z ceną skupu, inaczej pierwszy pasujący
                if (best == null || (it.maxPrice > 0 && (!(best.maxPrice > 0) || it.maxPrice < best.maxPrice))) best = it;
            }
            if (best == null) continue;
            double profit = potentialProfit(best, total, st.getCount(), c.taxFactor);
            out.add(new Highlight(i, profit >= c.amHighlightProfit, profit));
        }
        return out;
    }

    /** Zysk po odsprzedaniu oferty po cenie sprzedaży z listy (cena za sztukę x sztuki x podatek) minus cena oferty. */
    static double potentialProfit(MarketItem it, double total, int count, double tax) {
        double perPiece = it.sellPrice / Math.max(1, it.sellUnits());
        return perPiece * Math.max(1, count) * tax - total;
    }

    /** Czy rysować podświetlenie ofert (auto rynek snajpi, bez analizy). */
    static boolean highlighting() {
        return on && !paused && cfg().amBuy && cfg().amHighlight && !MarketAnalyzer.isRunning();
    }

    // ---------- pomocnicze ----------
    private static void click(MinecraftClient mc, HandledScreen<?> hs, int slot, int button) {
        mc.interactionManager.clickSlot(hs.getScreenHandler().syncId, slot, button, SlotActionType.PICKUP, mc.player);
    }

    /** Ile sztuk tego przedmiotu jest w ekwipunku (36 slotów). */
    static int countMatching(PlayerInventory inv, MarketItem it) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && ItemMatcher.matches(s, it)) n += s.getCount();
        }
        return n;
    }

    private static String onOff(boolean b) {
        return b ? "WŁ" : "WYŁ";
    }

    private static String keyName() {
        try {
            return RynekAnalizaMod.autoKey == null ? "F9" : RynekAnalizaMod.autoKey.getBoundKeyLocalizedText().getString();
        } catch (Throwable t) {
            return "F9";
        }
    }

    /** Komunikat na czat i do dziennika. */
    static void say(String msg) {
        note(msg);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) mc.player.sendMessage(Text.literal("[Auto rynek] " + msg), false);
    }

    /** Tylko do dziennika (zakładka "Auto rynek"). */
    static void note(String msg) {
        String t = java.time.LocalTime.now().withNano(0).toString();
        synchronized (log) {
            log.addLast(t + "  " + msg);
            while (log.size() > 60) log.removeFirst();
        }
    }

    private static void noteOnce(String key, String msg, long now) {
        Long until = notedOnce.get(key);
        if (until != null && now < until) return;
        notedOnce.put(key, now + 5 * 60_000L);
        if (notedOnce.size() > 200) {
            Iterator<Map.Entry<String, Long>> it = notedOnce.entrySet().iterator();
            while (it.hasNext()) if (it.next().getValue() < now) it.remove();
        }
        note(msg);
    }

    /** Ostatnie wpisy dziennika (najnowszy na końcu). */
    static List<String> logLines() {
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    /** Co teraz robi (do znaczka i zakładki). */
    static String stateLabel() {
        if (!on) return "wyłączony";
        if (paused) return "wstrzymany (analiza)";
        return switch (state) {
            case OPEN, WAIT_OPEN -> "otwiera rynek";
            case SORT, SORT_WAIT -> "ustawia sortowanie";
            case SNIPE -> "snajpi";
            case BUY_WAIT, CONFIRM, RESULT -> "kupuje";
            case SELL -> "wystawia";
            case AFK -> "anty-AFK (skoki)";
            case RESTART -> "restart rynku";
            case HOLD -> "czeka (otwarte inne okno)";
            case IDLE -> "tylko sprzedaż";
            default -> state.name().toLowerCase(Locale.ROOT);
        };
    }

    /** Za ile ms następne wystawianie / skoki / restart (do zakładki). */
    static long msToSell(long now) {
        long at = cfg().amBuy && cfg().amRestartSec > 0 ? (state == State.SNIPE ? sessionAt + cfg().amRestartSec * 1000L : now) : nextSellAt;
        return Math.max(0, Math.max(at, listLimitUntil) - now);
    }

    /** Za ile ms koniec przerwy po "brak miejsca na rynku" (0 = jest miejsce). */
    static long msToListSpace(long now) {
        return Math.max(0, listLimitUntil - now);
    }

    static long msToAfk(long now) {
        return Math.max(0, nextAfkAt - now);
    }

    static long msToRestart(long now) {
        int s = cfg().amRestartSec;
        return s <= 0 || state != State.SNIPE ? -1 : Math.max(0, sessionAt + s * 1000L - now);
    }

    /** Tylko testy: stan od zera. */
    static void resetForTests() {
        on = paused = false;
        state = State.OFF;
        cur = null;
        sellQueue.clear();
        selling = null;
        skipOffers.clear();
        notedOnce.clear();
        chatOkAt = chatFailAt = chatNoMoneyAt = chatListOkAt = chatListLimitAt = -1;
        noMoneyUntil = listLimitUntil = 0;
        listingBroken = false;
        sortConfirmed = sortGaveUp = false;
        sellDue = false;
        savedSlot = -1;
        hlSig = 0;
        hl = List.of();
        stats.bought = stats.listed = stats.restarts = stats.failed = stats.afk = 0;
        stats.spent = stats.listedValue = 0;
        synchronized (log) {
            log.clear();
        }
    }
}
