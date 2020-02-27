# CraftserveRadiation

Plugin do [Spigota](https://spigotmc.org/) dodający strefę radiacji, taką jak na [Kwadratowej Masakrze](https://kwadratowa.tv) (październik 2019).
Plugin działa na wersji [Minecraft 1.14 (Java Edition)](https://minecraft.net) (wersja 1.0) i wymaga zainstalowanego pluginu [WorldGuard](https://enginehub.org/worldguard/).

Autorem pluginu jest [TheMolkaPL](https://github.com/TheMolkaPL).

Konfiguracja
---

```yaml
# Czas trwania mikstury `Płyn Lugola` w minutach
potion-duration: 10
# Nazwa regionu WorldGuard wolny od radiacji, nie wymagający użycia mikstury
region-name: km_safe_from_radiation
# Lista nazwy światów w których działa plugin
world-names:
- world
```

Pierwsze uruchomienie
---

Przy pierwszym uruchomieniu pluginu potrzebujesz ustalić region w pluginie WorldGuard, który oznaczy obszar wolny od radiacji. W innym wypadku strefa radiacji nie będzie działać.

Potrzebujesz do tego różdżki WorldEdit, domyślnie jest to drewniana siekiera. Możesz ją sobie dać komendą `//wand`. Następnie używając tej różdżki musisz sobie zrobić zaznaczenie. Klikając LPM różdżką zaznaczasz pierwszy punkt, klikając PPM zaznaczasz drugi punkt. Gdy zaznaczysz oba punkty, a mają to być skrajne punkty, najdalej od siebie oddalone możesz powiększyć zaznaczenie, aby objęło całą wysokość świata - robisz to komendą `//expand vert`. W innym przypadku zaznaczenie będzie obejmowało wyłącznie obszar na wysokości którą zaznaczyłeś (czyli między oboma punktami).

Teraz posiadając zaznaczenie możesz sobie stworzyć region. Domyślna nazwa regionu to `km_safe_from_radiation`. Jeżeli ta nazwa nie została zmieniona w konfiguracji pluginu wpisujesz `/rg create km_safe_from_radiation`. Tak oto stworzyłeś region. Teraz musisz zdjąć z niego ochronę, ponieważ tylko OP może na nim budować i niszczyć. Robisz to komendą `/rg flag km_safe_from_radiation passthrough allow`.

Kolejnym krokiem jest zdjęcie radiacji z tego regionu. Robisz to komendą `/rg flag km_safe_from_radiation radiation no`. Na końcu zakładasz radiację na cały świat, to znaczy na już istniejący globalny region dla tego świata. Robisz to komendą `/rg flag __global__ radiation yes`.

Teraz po wyjściu z regionu wolnego od radiacji powinien pokazywać się boss bar oraz gracze powinni otrzymywać obrażenia.

Pobieranie
---

Najnowsze stabilne kompilacje znajdują sie w https://github.com/Craftserve/CraftserveRadiation/releases

Kompilacja
---

Projekt korzysta z [Apache Maven](https://maven.apache.org/). Wykonaj `mvn clean install` aby go zbudować. Obecnie plugin zależny jest od NMS. Potrzebujesz sobie lokalnie zbudować [Paper](https://github.com/PaperMC/Paper).
