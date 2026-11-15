# Rynek Analiza 0.4.0 - auto rynek z analizą cen (Minecraft 1.21.4, Fabric)

Samodzielny auto rynek dla Anarchii (BK-Rynek nie jest potrzebny) z wbudowaną analizą cen. **Jeden start / stop** (klawisz **F9**):
1. **analiza cen** wszystkich przedmiotów z listy (można ją pominąć: **F8** albo przycisk **"Pomiń analizę"** obok rynku),
2. **snajper** - kupuje oferty z zakładki **Najnowsze** taniej niż cena skupu; oferty z listy podświetla na **zielono** (zysk po sprzedaży
   od 200$) albo na **czerwono** (mniej),
3. **restart rynku co 60 s** - przy każdym restarcie **wystawia** kupione przedmioty; gdy na rynku nie ma miejsca, snajpi dalej,
4. **anty-AFK** co 4 minuty (zamyka rynek, 3 skoki, wraca),
5. **ponowna analiza co 12 minut** i od razu po 3 zakupach tego samego przedmiotu w ciągu 10 minut.
Zamiast snajpera można włączyć tryb **"Tylko sprzedaż"** - bot nie kupuje, tylko wystawia.

**Uczciwie:** 0.3.0 działała u Ciebie w grze. Zmiany z 0.4.0 (opisane niżej) były kompilowane i testowane tylko na atrapach API
Minecrafta i na symulowanym serwerze - w grze jeszcze nie były uruchomione. Prawdziwy build zrobi GitHub Actions.

## Nowość w 0.4.0
- **Jeden start / stop**: F9, przycisk w zakładce Auto rynek albo `/analiza auto` - najpierw analiza, potem snajper. Klawisz F8 nie
  startuje już osobnej analizy - teraz **pomija trwającą analizę**. Ręczna analiza została w zakładce Przedmioty (przycisk "Analizuj").
- **"Pomiń analizę"** (F8, przycisk obok okna rynku w trakcie analizy, `/analiza pomin`): analiza kończy się od razu, przedmioty już
  sprawdzone mają nowe ceny, reszta zostaje przy starych, a snajper rusza po 1 s.
- **Tempo analizy wg pingu** zamiast trybów Bezpieczna / Szybka / Maksymalna (opis niżej, "Szybkość analizy").
- **Podświetlanie ofert przy snajpieniu**: zielone = zysk co najmniej 200$, czerwone = mniej (próg w Ustawieniach).
- **Wystawianie przy każdym restarcie rynku** (zamiast co 3 minuty). Brak miejsca na rynku = snajper działa dalej, wystawianie wraca po
  sprzedaży którejś Twojej oferty albo po 5 minutach.
- **Tryb "Tylko sprzedaż"** (przycisk "Tryb: snajper / tylko sprzedaż" w zakładce Auto rynek).
- **Szybsze potwierdzenie zakupu**: przycisk potwierdzenia (także zielony barwnik bez napisu) jest klikany w tej samej klatce, w której
  przyszło okno - bez czekania na tick gry (wcześniej ok. 50-100 ms). Nowe oferty po odświeżeniu też są sprawdzane od razu w klatce.
- **Naprawiona ponowna analiza po zakupach**: działała tylko z włączonym autopilotem (domyślnie wyłączony). Teraz zawsze: 3 zakupy tego
  samego przedmiotu w ciągu 10 minut = analiza tego jednego przedmiotu od razu.
- Analiza co 12 minut nie wymaga już autopilota (przełącznik "Analiza: na starcie i co 12 min" w zakładce Auto rynek).

## Jak zbudować i zainstalować
1. Repo z plikami budowania (`build.gradle`, `gradle.properties`, `settings.gradle`, `.github/workflows/build.yml`) i folderem `src/`
   z tej paczki. Build leci w zakładce Actions, jar jest w artefakcie "rynek-analiza".
   - Na GitHubie (Add file -> Upload files) **przeciągnij cały folder `src`**, nie pojedyncze pliki. W repo musi być
     `src/main/java/pl/rynekanaliza/` z **19 plikami `.java`** (w 0.4.0 doszedł `PingTiming.java`) i `src/main/resources/fabric.mod.json`.
   - Wgraj też `gradle.properties` (wersja 0.4.0). `build.gradle` się nie zmienił.
   - Dobry jar `rynek-analiza-0.4.0.jar` ma ok. 210-250 KB. Jar ok. 1 KB = build bez plików moda.
2. Jar wrzuć do `mods/` (stary jar tego moda usuń). Ustawienia, lista przedmiotów i historia z 0.3.0 zostają.
   Jeśli to pierwsza instalacja: jar BK-Rynku z `mods/` usuń, a **folder z configiem BK zostaw** (`config/BK-Mods/BK-Rynek` albo
   `config/bkrynek-config`) - przy pierwszym uruchomieniu mod sam zaimportuje z niego listę (ponownie: "Importuj listę z BK").

## Auto rynek
**Start / stop:** klawisz **F9** (działa też przy otwartym rynku), przycisk w zakładce Auto rynek albo `/analiza auto`.
Panel zamyka się przy starcie. Na czacie: "[Auto rynek] Start: najpierw analiza cen, potem snajper (N przedm. z ceną skupu), wystawianie
przy każdym restarcie rynku, anty-AFK co 4 min, restart rynku co 60 s, analiza co 12 min. Stop: F9." i "Analiza cen: 25 przedm. Pomiń: F8
albo przycisk "Pomiń analizę" obok rynku." Stop w trakcie analizy zatrzymuje też analizę.

**Pętla (tryb snajper):**
1. **Analiza na starcie** (gdy włączona "Analiza: na starcie i co 12 min") - wszystkie przedmioty, bez przerwy przed nią. Po niej (albo
   po "Pomiń analizę"): "[Auto rynek] Analiza zakończona (zmienione ceny - sprzedaż: 7, skup: 5). Auto rynek wraca."
2. Wystawia przedmioty do wystawienia (punkt 7), otwiera rynek komendą z ustawień (domyślnie `/ah`) i ustawia sortowanie **Najnowsze**:
   PPM na przycisku sortowania (slot 53), aż w jego opisie zaznaczona będzie opcja "Najnowsze" (po 6 kliknięciach bez skutku - komunikat
   i snajpienie przy obecnym sortowaniu).
3. Przy każdej zmianie okna (od razu w klatce, w której przyszła nowa lista) sprawdza wszystkie oferty. Kupuje ofertę, gdy:
   - pasuje do przedmiotu z listy z **"Kup: TAK"** i ceną skupu **większą niż 0** (skup 0 albo brak = **nigdy**),
   - jest **taniej** niż skup (oferta równa skupowi - nie),
   - tryb **Unit** (domyślnie): cena za sztukę, oferta ma co najmniej "ilość kupna" sztuk; tryb **Sum**: cena całej oferty, która ma
     dokładnie "ilość kupna" sztuk,
   - nie jest Twoja (nick w opisie oferty) i nie jest droższa niż saldo konta (gdy saldo jest znane),
   - pet/kostium: ma co najmniej "minimum dni" z listy.
   Przy kilku pasujących przedmiotach liczy się ten z najniższym skupem.
4. **Podświetlenie**: każda oferta przedmiotu z listy (z ceną sprzedaży) ma ramkę - **zielona**, gdy po kupieniu i sprzedaży po cenie
   z listy zostanie co najmniej 200$ (cena sprzedaży za sztukę x sztuki x 0.97 podatku - cena oferty), **czerwona**, gdy mniej (także
   strata). Podróbki, własne oferty i przedmioty spoza listy - bez ramki. Próg i wyłączenie: Ustawienia.
5. Zakup: LPM na ofercie, a w oknie potwierdzenia mod **sprawdza, że to ten sam przedmiot i nie drożej** (inny przedmiot albo wyższa
   cena - "Anuluję zakup ...", okno zamknięte). Przycisk potwierdzenia: po nazwie ("potwierdź", "kup"/"zakup", "akceptuj", "confirm",
   "buy", "tak"; pomija sam przedmiot i "anuluj"/"nie"/"wróć"), po **zielonym barwniku** (lime/green dye), zapasowo slot z ustawień (11).
   Klik **w tej samej klatce, w której przyszło okno**, jeśli jest w nim już przycisk i kupowany przedmiot; gdy serwer dosyła zawartość
   po kawałku - czeka na nią najwyżej 0,25 s. Brak odpowiedzi serwera = ponowny klik po 4 x ping (min. 0,6 s), najwyżej 3 razy.
   Wynik z czatu ("Pomyślnie zakupiłeś...", "już sprzedany", "brak środków"...) albo z ekwipunku (przedmiot przybył).
   Brak środków = minuta przerwy w kupowaniu. Nieudana oferta jest pomijana przez 30 s, kupiona - przez minutę.
6. Co **200 ms** odświeża listę (PPM na slocie 0), ale nie częściej niż serwer odpowiada.
7. **Restart rynku co 60 s = wystawianie**: zamyka rynek, wystawia
   - przedmioty **kupione przez snajpera** (licznik "do wyst." przy przedmiocie),
   - wszystkie pasujące z ekwipunku, gdy przedmiot ma **"Wyst.: TAK"**,
   i otwiera rynek od nowa (gdy nie ma nic do wystawienia - zwykły restart z przerwą 1,5 s). Cena = cena sprzedaży z listy za "ilość
   sprzedaży" sztuk; mniejszy stack proporcjonalnie (48 jabłek przy 3000$ za 64 = 2250$). Komenda z ustawień (domyślnie
   `/wystaw {quantity} {price}`); przedmiot z plecaka jest na chwilę zamieniany z wybranym slotem paska. Przedmiot bez ceny sprzedaży
   nie jest wystawiany. Przy pełnym ekwipunku - wystawianie od razu.
   **Brak miejsca na rynku** (komunikat o limicie wystawionych): "Brak miejsca na rynku (limit wystawionych) - snajpię dalej; wystawię
   znowu po sprzedaży albo za 5 min." - snajper pracuje dalej, a wystawianie wraca, gdy serwer napisze o sprzedaży Twojej oferty
   (zwolniło się miejsce) albo po 5 minutach.
   Restart od razu także, gdy okno nie zmienia się przez 10 s, rynek się nie otworzy (5 razy z rzędu = stop) albo zatnie się okno
   potwierdzenia - po takim restarcie też najpierw wystawianie.
8. **Anty-AFK** co **4 minuty**: zamyka rynek, **3 skoki**, wraca na rynek.
9. **Analiza co 12 minut** (wszystkie przedmioty) i **ponowna analiza po zakupach** (niżej) - auto rynek stoi na czas analizy i sam wraca.
10. Gdy otworzysz inne okno (czat, ekwipunek, panel) - auto rynek czeka i wraca po jego zamknięciu. Prawy Shift przy otwartym rynku
    zamyka rynek i otwiera panel. Rozłączenie = stop. Pełny ekwipunek (i nic do wystawienia) = nie kupuje, dopóki nie zrobi się miejsce.

**Tryb "Tylko sprzedaż"** (przycisk "Tryb: snajper" -> "Tryb: tylko sprzedaż"; działa też w trakcie pracy): bot nie kupuje i nie
otwiera rynku. Co 60 s (ustawienie "restart rynku co") wystawia przedmioty z ekwipunku - kupione przez snajpera i wszystkie z
"Wyst.: TAK" (przełącznik "Wystawianie" jest wtedy zawsze WŁ). Anty-AFK i analiza co 12 minut działają dalej. Brak miejsca na rynku -
czeka na sprzedaż albo 5 minut. Rynek otwarty przez Ciebie nie jest zamykany - bot czeka, aż go zamkniesz.

**Ponowna analiza po zakupach:** gdy snajper kupi ten sam przedmiot **3 razy w ciągu 10 minut** (od jego ostatniej analizy), ten jeden
przedmiot jest analizowany od razu (najwcześniej 30 s po poprzedniej analizie): "[Analiza] Anarchiczny hełm: 3 zakupy w ciągu 10 min -
ponowna analiza." Działa z autopilotem i bez niego (przedmioty z "Auto: NIE" - bez ponownej analizy). Liczba zakupów i okno czasu:
Ustawienia ("Ponowna analiza po tylu zakupach", "Ponowna analiza: zakupy w ciągu (min)").

**Zakładka Auto rynek:** Start/Stop, "Tryb: snajper / tylko sprzedaż", Wystawianie, Anty-AFK, "Analiza: na starcie i co 12 min" i import
z BK. Pod spodem stan ("otwiera rynek", "snajpi", "kupuje", "wystawia", "tylko sprzedaż", "anty-AFK (skoki)", "restart rynku",
"czeka (otwarte inne okno)", "wstrzymany (analiza)"), liczniki, za ile wystawianie (restart) / skoki / analiza ("brak miejsca na rynku -
wystawianie po sprzedaży albo za X"), skąd ceny (zwykła analiza albo autopilot NA ŻYWO) i ping, lista przedmiotów z cenami
i przełącznikami **"Kup: TAK/NIE"** i **"Wyst.: TAK / kupione"** oraz dziennik.

**Ustawienia (grupa "Auto rynek"):** komenda rynku, odświeżanie (ms), restart co (s, 0 = nie; wtedy wystawianie co minutę), restart po
zacięciu (s), przerwa przy restarcie (ms), anty-AFK co (min) i liczba skoków, skup za sztukę (tryb Unit), podświetlanie ofert
(WŁ/WYŁ) i "zielone od zysku ($)" (200), sloty odświeżania / sortowania / potwierdzenia, nazwa przycisku potwierdzenia, komenda
wystawiania. Do tego "Analiza wszystkich co (min)" (12) i ustawienia ponownej analizy.

## Import z BK-Rynku
Mod szuka profili BK w `config/BK-Mods/BK-Rynek/auto_market_configs/*.json` (BK 3.0) i listy `servers[].prices` w głównym configu
(`config/BK-Mods/BK-Rynek/bkrynek-config.json` albo plik z `selected.txt`), a także w starym miejscu `config/bkrynek-config/`.
Bierze **aktywny profil** (`defaultProfile`; gdy jest w kilku plikach - najnowszy plik), a bez niego profil z Anarchii albo najnowszy.
Z profilu przechodzą też ustawienia: komenda rynku, sloty odświeżania / sortowania / potwierdzenia, nazwa potwierdzenia, wzór ceny
(`lore_regex`, `lore_seller_regex`), tryb Unit/Sum, komenda wystawiania i przerwa przy restarcie. Pola przedmiotów jak w BK.

## Panel (GUI)
- **Prawy Shift** otwiera/zamyka panel, **F9** = start/stop (analiza + auto rynek), **F8** = pomiń analizę. Klawisze zmienisz
  w zakładce **Ustawienia** (kliknij przycisk i naciśnij klawisz; Delete/Backspace = brak, Esc = anuluj) albo w Sterowaniu Minecrafta.
  W trakcie analizy panel się nie otwiera - najpierw ją pomiń (F8).
- Zakładki: **Przedmioty**, **Auto rynek**, **Autopilot**, **Historia**, **Ustawienia**.
- **Przedmioty**: lista przedmiotów, wyszukiwarka, **"Analizuj"** (ręczna analiza - z wpisaną frazą tylko pasujące; bez snajpera)
  i "Stop". W wierszu: nazwa, trend, **Śr. 3**, **Sprzedaż**, pole **Skup ($)** i **Wykres** (z paskiem autopilota).
- **Autopilot**: włączanie, tryb (próba / na żywo), następna analiza, statystyki dnia, progi skupu, saldo i dziennik decyzji.
- **Historia**: zakupy i sprzedaże ("Zakupy" / "Wg przedmiotów"), "Wyczyść historię".
- **Ustawienia**: klawisze, szybkość analizy (tylko podgląd pingu), min. ofert do ceny, podatek, skup (mnożnik, % salda, progi), ulepszenia
  %, komponenty, historia cen, obserwacja rynku, analiza co / ponowna analiza, autopilot, auto rynek.
- Pliki: ustawienia `config/rynek-analiza.json`, lista `config/rynek-analiza-przedmioty.json`, autopilot `config/rynek-analiza-autopilot.json`,
  historia zakupów `config/rynek-analiza-historia.json`, historia cen `config/rynek-analiza-history.json`,
  diagnostyka `config/rynek-analiza-diagnostyka.txt`.

## Skąd ceny (analiza i autopilot)
Analiza na starcie, co 12 minut i po zakupach wpisuje ceny na listę:
- **autopilot wyłączony albo w trybie PRÓBNYM**: jak zwykła analiza - sprzedaż = średnia 3 najtańszych, skup z progów (niżej).
  W próbie autopilot przy okazji liczy swoje decyzje (tylko dziennik w zakładce Autopilot, bez wpisywania).
- **autopilot NA ŻYWO**: ceny ustala autopilot (sprzedaż pod najtańszą ofertę, skup), przedmioty z "Auto: NIE" zostają przy swoich cenach.
Włączanie autopilota: zakładka Autopilot ("Włącz autopilota", tryb NA ŻYWO = "Tryb: PRÓBNY" i drugi klik w ciągu 5 s).

**Autopilot NA ŻYWO - cena sprzedaży** (za całą wystawkę): najtańsza cudza oferta minus 1$, nie niżej niż podłoga (koszt / 0.97 x 1.02,
85% zwykłej ceny, Twoje "Min. sprzedaż"); pomyłkowo tanie oferty pomijane; jedna tańsza oferta = czeka, od 2 = obniża; spadek ponad
20% musi się powtórzyć; w górę najwyżej 10% naraz. Między analizami autopilot czyta okna rynku otwierane przez snajpera: gdy co
najmniej 2 cudze oferty są tańsze od Twojej, obniża sprzedaż. **Zabezpieczenia:** budżet dzienny (25 000$), hamulec strat (20% budżetu),
max 3 niesprzedane zakupy na przedmiot, jeden zakup najwyżej 70% salda. **Tryb próbny**: "wirtualne zakupy" rozliczane po 2 h.

## Historia zakupów i sprzedaży
Każdy zakup snajpera (cena, sztuki) zapisuje się zawsze. **Sprzedaż** - dopiero gdy przedmiot naprawdę się sprzeda (komunikat serwera
o sprzedaży; po cenie wystawki x 0.97 albo samej cenie, także po cenach z ostatniej doby, albo po nazwie w komunikacie). Zysk po
podatku 3%. Widok "Wg przedmiotów": sprzedane z kupionych, wydane, zysk, średni czas do sprzedaży. "Wyczyść historię" z kopią.

## Cena sprzedaży (analiza)
Dla każdego przedmiotu: `/ah search <nazwa>` -> sortowanie "Najniższej ceny" -> cena za sztukę z każdej pasującej oferty ->
`sprzedaż = floor( średnia 3 najtańszych za sztukę x ilość sprzedaży )`. Mniej ofert niż "Min. ofert do ceny" (3) = bez zmian.
Zapasowe frazy, gdy po pełnej nazwie nie ma żadnego przedmiotu tego id ("Anarchiczny hełm II" -> "Anarchiczny hełm" -> "Anarchiczny").
Przedmioty niestackowalne - skan do 3 ofert; stackowalne - wszystkie strony.

## Co dzieje się podczas analizy
1. Auto rynek wstrzymany, jego okno rynku zamknięte, **przerwa** (domyślnie 3 s), żeby spóźnione odpowiedzi serwera nie mieszały się
   z analizą. Analiza na starcie rusza bez tej przerwy (auto rynek jeszcze nic nie kliknął).
2. Analiza przedmiotów (wyżej) - po cichu, komunikaty w logu gry. Nad rynkiem pasek z przedmiotem i pingiem, obok okna rynku przycisk
   **"Pomiń analizę"**.
3. Rynek zamknięty, **przerwa** (3 s; po "Pomiń analizę" najwyżej 1 s), lista zapisana, podsumowanie na czacie i auto rynek rusza od
   nowa. Długość przerwy: Ustawienia, "Przerwa przed i po analizie, gdy działa auto rynek (s)" (0-15 s).

**Przedmioty o tej samej nazwie** (np. 5 x "Zaklęta książka") są rozróżniane po pierwszej linii lore (potem CMD / enchanty).
Własna fraza wyszukiwania (`search` w `config/rynek-analiza.json`) wyłącza zapasowe frazy dla tego przedmiotu.

## Cena skupu
`skup (za sztukę) = cena rynku x 0.96 x (1 - marża progu)` - analiza i autopilot liczą tak samo.

| cena za sztukę | marża | przykład | zostaje na czysto |
|---|---|---|---|
| do 100$ | 8,5% | złote jabłko 70$ -> skup 61,48$ | ok. 6,40$ za sztukę |
| do 1 000$ | 33% | diamentowa część 300$ -> skup 192,96$ | ok. 97$ |
| do 15 000$ | 5% | Anarchiczna I 9 999$ -> skup 9 119$ | ok. 579$ |
| drożej | 9% | Anarchiczna II 22 000$ -> skup 19 219$ | ok. 2 120$ |

Przy granicy progu marża zmienia się płynnie. Skup nigdy wyżej niż sprzedaż po podatku z min. zyskiem, niż 70% salda i niż "Max skup".
Przedmiot z "Auto: NIE" zostaje przy swoim skupie. W trybie Sum skup dotyczy całej oferty "ilość kupna" sztuk. Skup wpiszesz też ręcznie
w polu **Skup ($)** (zapis ok. 1 s po ostatnim znaku).

## Szybkość analizy (wg pingu)
Bot zawsze czeka na faktyczną odpowiedź serwera (okno przestało się zmieniać). Ile czekać, bierze z pingu - z listy graczy (Tab),
a gdy go tam nie ma, z pomiaru czasu od kliknięcia do odpowiedzi serwera:

| ping | strona wczytana po (ticki bez zmian) | przerwa między kliknięciami | przed kolejnym "/ah search" |
|---|---|---|---|
| do 60 ms | 1 | 1 tick | 0,1-0,15 s |
| do 150 ms | 2 | 2 ticki | 0,15-0,25 s |
| do 300 ms | 3 | 3 ticki | 0,25-0,4 s |
| więcej | 4 | 3 ticki | od 0,4 s (najwyżej 1 s) |

Limity czekania na odpowiedź rosną z pingiem. Tempo liczy się od nowa przed każdym przedmiotem. Ping widać w zakładce Auto rynek,
w Ustawieniach i na pasku nad rynkiem w trakcie analizy.

## Dopasowanie i cena - jak w BK
- Najpierw id przedmiotu, potem zakazane lore, potem nazwa bez kolorów (bez wielkości liter, czcionka "small caps" jak zwykłe litery).
  Gdy nazwa na liście ma kolor (np. `§4Anarchiczny hełm`), pierwszy kolor nazwy oferty i jej pogrubienie/podkreślenie też muszą się
  zgadzać (`§f` = bez wymagania koloru).
- Wymagane lore: każda część (rozdzielone `;` albo nową linią) musi być w opisie przedmiotu; zakres `Dodatkowe obrażenia 0-100%`
  pasuje do liczby z zakresu; linie z ceną, sprzedawcą, "kliknij", wygaśnięciem są pomijane.
- Enchanty z mapy `enchantments` (min. poziom; z przedmiotu albo z opisu), zakazane enchanty, CMD, efekty mikstur. Z opisu liczy się
  tylko pełna nazwa: "Ochrona przed ogniem V" to nie "Ochrona", "Morskie szczęście III" to nie "Szczęście".
- **Ulepszenia %** ("Pomijaj ulepszenia %", domyślnie TAK): analiza liczy 3 najtańsze przedmioty tej nazwy niezależnie od linii z %.
  **Snajper kupuje dokładnie wg listy** (żeby kupował każdy %, wpisz zakres `Dodatkowe obrażenia 0-100%`).
- Cena oferty: wzór z profilu (`lore_regex`), pomija linie "kosztuje ... sztuk" i sprzedawcę; liczby "20 370", "3,289", "1.5k", "2mld".
- Diagnostyka przy braku ofert: ile przedmiotów tego id było, ile odrzuconych i dlaczego, pełny opis w `config/rynek-analiza-diagnostyka.txt`.

## Podgląd na rynku i znaczek
- W trakcie analizy: pasek nad rynkiem (przedmiot, kolejka, strona, liczba ofert, ping), **żółte** ramki na 3 najtańszych ofertach,
  przycisk "Pomiń analizę" obok okna. Przy snajpieniu: **zielone / czerwone** ramki ofert z listy (zysk).
- Znaczek "Rynek Analiza: działa" przy oknie rynku (stan auto rynku, co mod odczytał z okna; w trakcie analizy "analiza auto rynku")
  i jedna linia na ekranie gry, gdy auto rynek pracuje, a rynek jest zamknięty ("analiza wszystkich za 8 min (25)"). Chowa się pod F1.
  Wyłączenie: Ustawienia, "Znaczek 'działa'". Podgląd analizy: "Podgląd na rynku".

## Komendy (opcjonalnie)
`/analiza auto` (start/stop), `/analiza pomin` (pomiń analizę), `/analiza start [fragment]` i `/analiza stop` (ręczna analiza),
`/analiza lista`.

## Do sprawdzenia w grze (0.4.0)
1. F9: na czacie start, potem analiza (pasek nad rynkiem z pingiem i przycisk "Pomiń analizę" po prawej stronie okna). Kliknij
   "Pomiń analizę" albo F8 - po ok. 1 s snajper otwiera rynek.
2. Przy snajpieniu oferty z listy mają zielone/czerwone ramki. Najedź na ofertę i policz: cena sprzedaży z listy x 0.97 - cena oferty.
3. Zakup: czy potwierdzenie (zielony barwnik) klika się szybciej i czy zakup przechodzi jak w 0.3.0.
4. Po 60 s restart rynku: najpierw wystawianie kupionych, potem rynek. Gdy rynek jest pełny - komunikat "Brak miejsca na rynku" i snajper
   dalej. Przyślij treść komunikatu serwera o limicie, jeśli mod go nie rozpozna (wtedy każde wystawienie czeka 3 s na potwierdzenie).
5. "Tryb: tylko sprzedaż" - rynek zamknięty, wystawianie co 60 s.
6. 3 zakupy tego samego przedmiotu w 10 minut - na czacie "ponowna analiza" i analiza tylko tego przedmiotu.

## Historia cen
`config/rynek-analiza-history.json`, do ok. 1000 punktów na przedmiot. Źródła: analizy (domyślnie jedyne na wykresie) i obserwacje okien
rynku - zapisywane tylko wtedy, gdy okno pokazuje prawdziwe minimum (jedna strona albo strona 1 od najtańszych), więc okna snajpera
z sortowaniem "Najnowsze" zwykle nie są zapisywane. Obserwacje pokażesz na wykresie przełącznikiem "Wykresy: też obserwacje rynku".
