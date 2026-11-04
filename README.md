# LA Radars

Application Android qui prévient le conducteur à l'approche d'un des 125 radars de vitesse
de la ville de Los Angeles (programme LADOT « Speed Safety System »), même quand Google Maps,
Waze ou la musique est à l'écran.

## Dossiers

- `data/` — la liste officielle des radars et le script qui la convertit en coordonnées GPS.
  - `cameras.csv` : les 125 tronçons, tels que publiés par LADOT (rue, de, à, limite, statut).
  - `cameras.json` : la même liste avec les coordonnées GPS de chaque extrémité (ce que l'app embarque).
  - `geocode.py` : le script qui fabrique `cameras.json` à partir de `cameras.csv` en interrogeant
    la couche « Intersections » du Bureau of Engineering de la ville.
  - `liste-radars.md` : la liste lisible, par district.
- `android/` — le projet Android (Kotlin). L'APK à installer sort dans
  `android/app/build/outputs/apk/release/app-release.apk` (une copie est à la racine : `LA-Radars.apk`).

## Précision des emplacements

LADOT publie des tronçons (« rue, de X à Y »), pas des points. Chaque extrémité a été convertie en
coordonnées via les intersections officielles de la ville ; 10 cas particuliers (rivière, canal,
autoroute, adresse, intersections absentes de la base) sont fixés à la main dans `geocode.py`
(`MANUAL`). L'app alerte quand on est à moins de la distance réglée de n'importe quel point du
tronçon. Un tronçon est très long : Vista Del Mar (11-6, « City Limit → Culver Blvd », 5 km),
l'alerte y couvre donc toute la route côtière.

## Installer l'app sur un téléphone Android

1. Envoyer le fichier `app-release.apk` sur le téléphone (AirDrop-équivalent, lien, email, Drive…).
2. Ouvrir le fichier. Android demande d'autoriser l'installation depuis cette source : accepter.
3. Lancer **LA Radars**, appuyer sur **Start driving mode**, accepter la position et les notifications.
4. Appuyer sur **Allow running in background (battery)** si le bouton apparaît, puis « Autoriser » :
   sans ça, certains téléphones (Samsung, Xiaomi…) coupent le GPS après quelques minutes.

Ensuite tu peux passer sur Google Maps : l'alerte arrive en notification, en vibration et à la voix.

## Fonctionnalités (v1.2)

- Alerte seulement si l'on **se dirige vers** le radar (une rue parallèle ne déclenche rien), jamais
  avant la distance réglée (100 m / 350 ft minimum).
- Séquence vocale : approche → mi-distance → « Camera zone » → « Camera passed ». Bips répétés tant
  qu'on roule à plus de 10 mph au-dessus de la limite dans la zone.
- Réglages : miles ou kilomètres, voix / son / vibration, radars actifs seulement.
- Démarrage automatique : quand le téléphone détecte la conduite, ou quand le Bluetooth de la voiture
  se connecte (nécessite la position « Autoriser tout le temps »).
- Carte OpenStreetMap des 125 tronçons, recherche par rue, filtre par district, ouverture dans
  Google Maps.
- Historique des trajets (durée, distance, zones traversées, excès).
- Mise à jour en ligne de la liste des radars et de l'app elle-même (voir ci-dessous).
- Bouton « I see the camera here » pour épingler l'emplacement exact d'un radar.
- Widget d'écran d'accueil et raccourci « Start driving » (appui long sur l'icône).

## Mise à jour en ligne (app + données)

L'app lit `data/manifest.json` publié sur GitHub
(`https://raw.githubusercontent.com/Bastientiger/la-radars/main/data/manifest.json`) une fois par
jour au démarrage d'un trajet, ou via « Check for updates » dans les réglages. Le manifeste indique
la version des données (`data_version`, `data_url`) et de l'app (`app_version_code`, `apk_url`).

Pour publier une nouvelle version :
1. Augmenter `versionCode` / `versionName` dans `android/app/build.gradle.kts`, compiler l'APK.
2. Créer une release GitHub `vX.Y` avec `LA-Radars.apk` en pièce jointe.
3. Mettre à jour `data/manifest.json` (`app_version_code`, `app_version_name`, `apk_url`) et, si la
   liste a changé, `data_version` + `data/cameras.json`. Pousser sur `main`.

Sur le téléphone : « Check for updates » → « Download » → Android demande d'installer. La première
fois, Android demande d'autoriser LA Radars à installer des apps (réglage « Sources inconnues »).

## Fonctionnement

- Au démarrage du mode conduite, un service de premier plan (notification permanente « LA Radars
  is watching ») lit le GPS une fois par seconde.
- Pour chaque position, l'app calcule la distance au tronçon de chaque radar. Quand elle passe sous
  la distance d'alerte (réglable, 400 m par défaut), une alerte part : notification sonore + vibration
  + annonce vocale (« Speed camera in 350 m. Limit 35. »). Si la vitesse GPS dépasse la limite de
  plus de 10 mph, l'annonce commence par « Slow down! ».
- Le même radar ne sonne qu'une fois par passage : il se réarme quand on s'en éloigne de plus de
  150 m au-delà de la distance d'alerte.
- Les radars en construction sont signalés aussi (ils seront tous actifs fin 2026) ; le statut
  est indiqué dans l'alerte.

## Mettre à jour la liste des radars

Quand LADOT change le statut (construction → actif) ou les emplacements :

1. Mettre à jour `data/cameras.csv`.
2. Lancer `python3 -I data/geocode.py` (quelques minutes).
3. Copier `data/cameras.json` dans `android/app/src/main/assets/cameras.json`.
4. Recompiler l'APK (voir ci-dessous) et le réinstaller : Android le met à jour en place.

## Recompiler l'APK

```bash
cd android && JAVA_HOME=~/Library/Java/jdk17/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ./gradlew assembleRelease
```

La signature utilise `android/la-radars-release.jks` et `android/keystore.properties`. **Ne pas
les perdre** : une mise à jour de l'app ne s'installe par-dessus l'ancienne que si elle est signée
avec la même clé.
