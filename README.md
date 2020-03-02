# CraftserveRadiation

Plugin do [Spigota](https://spigotmc.org/) dodający strefę radiacji, taką jak na [Kwadratowej Masakrze](https://kwadratowa.tv) (październik 2019).
Plugin działa na [Minecraft Java Edition](https://minecraft.net) na wersji 1.14 oraz 1.15. Wymaga zainstalowanego pluginu [WorldGuard](https://enginehub.org/worldguard/).

Autorem pluginu jest [TheMolkaPL](https://github.com/TheMolkaPL).

Pierwsze uruchomienie
---

Przy pierwszym uruchomieniu pluginu potrzebujesz ustalić region w pluginie WorldGuard, który oznaczy obszar radioaktywny. W innym wypadku strefa radiacji nie będzie działać.

Najpierw stwórz obszar wolny od radiacji. Potrzebujesz do tego różdżki WorldEdit, domyślnie jest to drewniana siekiera. Możesz ją sobie dać komendą `//wand`. Następnie używając tej różdżki musisz sobie zrobić zaznaczenie. Klikając LPM różdżką zaznaczasz pierwszy punkt, klikając PPM zaznaczasz drugi punkt. Gdy zaznaczysz oba punkty, a mają to być skrajne punkty, najdalej od siebie oddalone możesz powiększyć zaznaczenie, aby objęło całą wysokość świata - robisz to komendą `//expand vert`. W innym przypadku zaznaczenie będzie obejmowało wyłącznie obszar na wysokości którą zaznaczyłeś (czyli między oboma punktami).

Teraz posiadając zaznaczenie możesz sobie stworzyć region. W tym poradniku nazwiemy go `km_safe_from_radiation`. Stwórz region wpisując `/rg create km_safe_from_radiation`. Teraz musisz zdjąć z niego ochronę, ponieważ tylko OP może na nim budować i niszczyć. Jest to domyślne zachowanie regionów po ich stworzeniu w WorldGuard. Robisz to komendą `/rg flag km_safe_from_radiation passthrough allow`.

Kolejnym krokiem jest zdjęcie radiacji z tego regionu. Robisz to komendą `/rg flag km_safe_from_radiation radiation no`. Na końcu zakładasz radiację na cały świat, to znaczy na już istniejący globalny region dla tego świata. Robisz to komendą `/rg flag __global__ radiation yes`.

Teraz po wyjściu z regionu wolnego od radiacji powinien pokazywać się boss bar oraz gracze powinni otrzymywać obrażenia.

Pobieranie
---

Najnowsze stabilne kompilacje znajdują sie w https://github.com/Craftserve/CraftserveRadiation/releases

Kompilacja
---

Projekt korzysta z [Apache Maven](https://maven.apache.org/). Wykonaj `mvn clean install` aby go zbudować.
