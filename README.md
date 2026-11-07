# Passe-Mot (prototype v0.1)

Messagerie entre téléphones Android, sans internet (Nearby Connections).

## Compiler l'APK avec GitHub (gratuit)

1. Sur github.com : **New repository**, nom `PasseMot`, puis **Create repository**.
2. Clique sur **uploading an existing file** et glisse TOUT le contenu du dossier `PasseMot` (dézippé).
3. Vérifie que le dossier `.github/workflows/build.yml` est bien présent dans le dépôt.
   S'il manque : **Add file > Create new file**, tape `.github/workflows/build.yml` comme nom,
   et colle le contenu du fichier `build.yml` du zip.
4. Clique sur **Commit changes**.
5. Onglet **Actions** : la compilation démarre toute seule (3 à 6 minutes).
6. Quand elle est verte, ouvre-la et télécharge **PasseMot-apk** (en bas, section Artifacts).
7. Dézippe, envoie `app-debug.apk` sur les téléphones et installe-le
   (autorise « installer des applis inconnues » si Android le demande).

## Tester

Sur les 2 téléphones : active Bluetooth + Wi-Fi (et la localisation sur Android 11 ou moins),
ouvre Passe-Mot, accepte les permissions, appuie sur « Démarrer le réseau ».
Les téléphones se trouvent tout seuls, puis écris un message et appuie sur « Envoyer ».

## En cas d'erreur de compilation

Ouvre le job rouge dans Actions, copie les dernières lignes du message d'erreur
et envoie-les à Claude.
