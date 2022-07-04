CraftserveRadiation ![Build](https://github.com/Craftserve/CraftserveRadiation/workflows/Build/badge.svg) ![Deploy](https://github.com/Craftserve/CraftserveRadiation/workflows/Deploy/badge.svg)
===================

Plugin do [Spigota](https://spigotmc.org/) dodający strefę radiacji, taką jak na [Kwadratowej Masakrze](https://kwadratowa.tv) (październik 2019).
Plugin działa na [Minecraft Java Edition](https://minecraft.net) na wersji 1.14, 1.15, 1.16, 1.17, 1.18 oraz 1.19. Wymaga zainstalowanego pluginu [WorldGuard](https://enginehub.org/worldguard/).
bStats: https://bstats.org/plugin/bukkit/CraftserveRadiation/13487

Autorem pluginu jest [TheMolkaPL](https://github.com/TheMolkaPL).

Pierwsze uruchomienie
---

Przy pierwszym uruchomieniu pluginu potrzebujesz ustalić region który oznaczy obszar jako wolny od radiacji. W innym wypadku strefa radiacji nie będzie działać. Użyj komendy `/radiation safe <radius>` by stworzyć obszar wolny od radiacji w formie sześcianu (cuboid) w promieniu `radius` od twojej obecnej lokalizacji. Region zostanie założony na pełną wysokość świata. Teraz po wyjściu z regionu wolnego od radiacji powinien pokazywać się boss bar oraz gracze powinni otrzymywać obrażenia.

Pobieranie
---

Najnowsze stabilne kompilacje znajdują sie w https://github.com/Craftserve/CraftserveRadiation/releases

Kompilacja
---

Projekt korzysta z [Apache Maven](https://maven.apache.org/). Wykonaj `mvn clean install` aby go zbudować.
