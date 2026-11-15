package pl.rynekanaliza;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Tempo analizy dopasowane do pingu (zamiast dawnych trybów "bezpieczna / szybka / maksymalna").
 *
 * Ping bierzemy z listy graczy (to, co pokazuje Tab), a gdy go nie ma - z pomiaru: ile czasu mija od kliknięcia
 * w oknie rynku do odpowiedzi serwera (średnia krocząca). Z pingu wynika:
 *  - ile ticków okno musi stać bez zmian, żeby uznać stronę za wczytaną (STABLE_TICKS),
 *  - przerwa między kliknięciami (MIN_CLICK_GAP_TICKS) i przed kolejnym "/ah search" (BETWEEN_TARGETS_TICKS),
 *  - jak długo czekać na odpowiedź, zanim uznamy, że nie przyszła (limity czasu).
 * Bot i tak czeka na faktyczną zmianę okna - ping ustala tylko zapas na wolniejsze łącze.
 */
public final class PingTiming {
    private PingTiming() {}

    /** Ping z listy graczy w ms; -1 = nieznany (podmieniane w testach). */
    static IntSupplier serverPing = PingTiming::readServerPing;
    /** Zegar pomiarów odpowiedzi (podmieniany w testach). */
    static LongSupplier clock = System::currentTimeMillis;
    /** Gdy pingu nie ma ani z listy graczy, ani z pomiaru. */
    static final int DEFAULT_MS = 100;

    private static double measured = -1;
    private static int samples;

    static int readServerPing() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null || mc.player.networkHandler == null) return -1;
            PlayerListEntry e = mc.player.networkHandler.getPlayerListEntry(mc.player.getUuid());
            return e == null ? -1 : e.getLatency();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Zmierzony czas od kliknięcia do odpowiedzi serwera (ms). */
    static void onResponse(long ms) {
        if (ms < 0 || ms > 10_000) return;
        measured = measured < 0 ? ms : measured * 0.75 + ms * 0.25;
        samples++;
    }

    /** Ping do obliczeń: z listy graczy, inaczej z pomiaru, inaczej domyślny. */
    static int estimateMs() {
        int p;
        try {
            p = serverPing.getAsInt();
        } catch (Throwable t) {
            p = -1;
        }
        if (p > 0) return Math.min(p, 5000);
        if (samples > 0 && measured >= 0) return (int) Math.min(5000, Math.round(measured));
        return DEFAULT_MS;
    }

    /** Skąd jest ping (do panelu): "z listy graczy", "zmierzony", "domyślny". */
    static String source() {
        int p;
        try {
            p = serverPing.getAsInt();
        } catch (Throwable t) {
            p = -1;
        }
        if (p > 0) return "z listy graczy";
        return samples > 0 ? "zmierzony" : "domyślny";
    }

    static int stableTicks(int ms) {
        return ms <= 60 ? 1 : ms <= 150 ? 2 : ms <= 300 ? 3 : 4;
    }

    static int clickGapTicks(int ms) {
        return ms <= 60 ? 1 : ms <= 150 ? 2 : 3;
    }

    static int betweenTargetsTicks(int ms) {
        return clamp(2 + ms / 50, 2, 20);
    }

    /** Ustawia tempo silnika analizy wg pingu (przed każdym przedmiotem). */
    static void apply() {
        int ms = estimateMs();
        MarketAnalyzer.STABLE_TICKS = stableTicks(ms);
        MarketAnalyzer.MIN_CLICK_GAP_TICKS = clickGapTicks(ms);
        MarketAnalyzer.BETWEEN_TARGETS_TICKS = betweenTargetsTicks(ms);
        MarketAnalyzer.WAIT_TIMEOUT_TICKS = clamp(40 + ms / 25, 40, 200);
        MarketAnalyzer.PAGE_TIMEOUT_TICKS = clamp(80 + ms / 25, 80, 240);
        MarketAnalyzer.OPEN_TIMEOUT_TICKS = clamp(120 + ms / 25, 120, 300);
    }

    /** Krótki opis do panelu i paska nad rynkiem, np. "ping 85 ms - strona: 2 ticki bez zmian". */
    static String describe() {
        int ms = estimateMs();
        int st = stableTicks(ms);
        return "ping " + ms + " ms (" + source() + ") - strona wczytana po " + st + (st == 1 ? " ticku" : " tickach") + " bez zmian";
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Tylko testy. */
    static void resetForTests() {
        measured = -1;
        samples = 0;
    }
}
