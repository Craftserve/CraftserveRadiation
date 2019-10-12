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

Pobieranie
---

Najnowsze stabilne kompilacje znajdują sie w https://github.com/Craftserve/CraftserveRadiation/releases

Kompilacja
---

Projekt korzysta z [Apache Maven](https://maven.apache.org/). Wykonaj `mvn clean install` aby go zbudować.
