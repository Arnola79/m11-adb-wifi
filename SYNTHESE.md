# Synthèse : piloter le FiiO M11 depuis le Pixel avec scrcpy

*Mise à jour : 9 novembre 2026*

## Le problème

- Le FiiO M11 tourne sous une ancienne version d'Android, qui n'a pas le « débogage sans fil » natif.
- Le mode ADB en Wi‑Fi s'active avec `adb tcpip 5555`, mais **il s'efface à chaque redémarrage du M11**
  (et parfois quand on désactive puis réactive le débogage USB).
- Il faut alors relancer `adb tcpip 5555` **par câble USB** depuis un appareil hôte (PC ou Pixel).
- Le M11 n'est pas rooté, donc on ne peut pas rendre ce réglage permanent (`persist.adb.tcp.port`).

## Infos utiles

| Élément | Valeur |
|---|---|
| M11, numéro de série ADB | `000004f46db0b364` |
| M11, IP Wi‑Fi (au 9/11/2026) | `192.168.1.44` → adresse scrcpy : `192.168.1.44:5555` |
| M11, nom vu en USB | « Exynos » (FiiO, ID USB `2972:0045`) |
| Compte GitHub | `Arnola79` |
| Dépôt de l'appli | https://github.com/Arnola79/m11-adb-wifi |
| APK (toujours la dernière version) | https://github.com/Arnola79/m11-adb-wifi/releases/download/latest/M11-ADB-WiFi.apk |

Conseil : réserve l'IP `192.168.1.44` au M11 dans ta box (réservation DHCP), sinon l'adresse peut changer.

## Remettre le Wi‑Fi ADB après un redémarrage du M11

### Option A : depuis le Pixel, avec l'appli maison « M11 ADB Wi‑Fi »
1. Branche le M11 au Pixel avec un câble USB‑C qui transporte les données.
2. Débogage USB et Wi‑Fi activés sur le M11.
3. Ouvre l'appli et appuie sur **Activer ADB Wi‑Fi**.
4. Accepte l'accès USB sur le Pixel, puis **« Toujours autoriser »** sur le M11 (la première fois seulement).
5. L'appli affiche l'adresse (ex. `192.168.1.44:5555`). Débranche le câble et connecte scrcpy dessus.

Si l'appli dit « aucun appareil ADB trouvé », le Pixel n'est pas en mode hôte : dans la notification USB du Pixel,
choisis « Cet appareil contrôle le périphérique connecté », ou retourne le câble.

### Option B : depuis le PC
```bash
adb devices          # le M11 doit apparaître
adb tcpip 5555
```

### Option C : sans Wi‑Fi
scrcpy for Android sait aussi piloter le M11 **directement par câble USB**, sans aucune commande.

## Pièges rencontrés

- Le bouton « adb shell » de scrcpy for Android ouvre un terminal **dans le M11**. On ne peut pas y lancer
  `tcpip 5555`, qui est une commande de l'appareil hôte.
- `gh auth login` : le code à usage unique s'affiche **dans le terminal**, il n'est pas envoyé par mail.
  Solution retenue : un jeton GitHub « classic » (droits `repo` et `workflow`) collé dans `gh auth login`.
- Version 1.0 de l'appli : le contenu passait sous la barre de titre (affichage plein écran d'Android 15).
  C'est corrigé dans la 1.1.
- Le passage de la 1.0 à la 1.1 a demandé une désinstallation, parce que la signature a changé.
  Depuis la 1.1, la signature est fixe (`app/debug.keystore`) : les mises à jour s'installent par-dessus.

## L'appli « M11 ADB Wi‑Fi » (ce dépôt)

- Kotlin, sans aucune bibliothèque externe. Elle implémente le protocole ADB côté hôte, en USB OTG :
  - `AdbUsb.kt` : messages ADB (CNXN, AUTH, OPEN, WRTE, OKAY, CLSE) sur la connexion USB en mode bulk ;
  - `AdbKeys.kt` : clé RSA 2048 conservée dans l'appli, signature du jeton, clé publique au format adbd ;
  - `MainActivity.kt` : l'interface, la permission USB, puis `shell:ip …` (pour lire l'IP) et `tcpip:5555`.
- Compilation : GitHub Actions (`.github/workflows/build.yml`) à chaque push sur `main`. L'APK est publié
  dans la release `latest`.
- Pour modifier l'appli : édite le code, puis `git push`. Le nouvel APK est en ligne au même lien quelques minutes après.
