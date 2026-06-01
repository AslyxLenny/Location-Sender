# Location Sender

Partage de position GPS en temps réel entre deux appareils Android sur le même
réseau local. Un appareil **émet** sa position GPS réelle ; l'autre la **reçoit**
et l'injecte comme **position fictive** (mock location), avec un facteur de
vitesse réglable.

Aucune configuration réseau : l'émetteur **diffuse en broadcast UDP**, donc pas
d'adresse IP à saisir ni de recherche d'appareil.

---

## Fonctionnement

```
┌──────────────────────┐        UDP broadcast         ┌──────────────────────┐
│      ÉMETTEUR         │   (position GPS, ~1/s)       │      RECEIVER        │
│  lit le GPS réel  ───────────────────────────────▶  │  injecte en mock     │
│  et le diffuse       │      port 8080 (défaut)      │  location (× facteur)│
└──────────────────────┘                              └──────────────────────┘
```

Chaque appareil a **un rôle**, choisi au premier lancement (modifiable ensuite
dans les Réglages) :

- **Émetteur** — lit la position GPS réelle de l'appareil et la diffuse sur le
  réseau local une fois par seconde.
- **Receiver** — écoute le réseau et applique la position reçue comme position
  fictive Android, après application d'un facteur de vitesse optionnel.

## Fonctionnalités

- 📡 **Diffusion broadcast** — pas d'IP à configurer, l'émetteur diffuse à tout
  le sous-réseau.
- 🎚️ **Facteur de vitesse** (receiver) — multiplie la vitesse reçue par un
  curseur de `×0` à `×1` (pas de 0,05).
- 🔀 **Activation progressive** — activer/désactiver le facteur fait varier la
  vitesse en douceur (≈ 5 s de 0,7 à 1,0) au lieu d'un saut brutal.
- 📱 **Interface adaptative** — deux colonnes sur tablette / écran large, une
  seule sur téléphone.
- 🔔 **Services foreground** — l'émission et l'écoute continuent en arrière-plan
  avec une notification persistante.

## Prérequis

- Android **13 (API 33)** ou supérieur.
- Les deux appareils sur le **même réseau Wi-Fi**, avec le **même port**.
- Sur le **receiver** : l'app doit être sélectionnée comme application de
  position fictive (voir ci-dessous).

## Installation

Projet Gradle standard. Cloner puis ouvrir dans Android Studio, ou en ligne de
commande :

```bash
git clone https://github.com/<votre-utilisateur>/LocationSender.git
cd LocationSender
./gradlew installDebug
```

> `local.properties` (chemin du SDK) n'est pas versionné : Android Studio le
> génère automatiquement à l'ouverture du projet.

## Utilisation

1. **Au premier lancement**, choisir le rôle de l'appareil : *Émetteur* ou
   *Receiver*.
2. Sur le **receiver**, activer la position fictive (une seule fois) :
   - Activer les **Options pour développeurs** (Paramètres → À propos → taper
     7 fois sur « Numéro de build »).
   - Options développeur → **Application de position fictive** → choisir
     **Location Sender**.
   - L'app propose un bouton raccourci vers cet écran.
3. Sur le **receiver**, appuyer sur **Démarrer l'écoute**. Le bandeau passe au
   vert (« Position fictive active »).
4. Sur l'**émetteur**, appuyer sur **Démarrer la diffusion**.
5. Le receiver affiche la position reçue ; les apps de l'appareil receiver
   voient désormais cette position comme leur GPS.

### Facteur de vitesse

Sur l'écran receiver, la carte **Vitesse simulée** permet de multiplier la
vitesse transmise au mock par un facteur de 0 à 1. L'interrupteur active ou
désactive la transformation ; le changement est progressif.

### Réglages

L'icône ⚙️ en haut à droite donne accès au **port** d'écoute/diffusion et au
**changement de rôle** (qui arrête le service en cours).

## Dépannage

| Symptôme | Cause probable / solution |
|----------|---------------------------|
| Le receiver ne reçoit rien | Les deux appareils doivent être sur le même Wi-Fi et le même port. Certains points d'accès activent l'**isolation des clients** (AP/client isolation) qui bloque le broadcast entre appareils — désactiver cette option sur le routeur. |
| « Position fictive non autorisée » | Sélectionner Location Sender comme application de position fictive dans les Options pour développeurs. |
| L'app n'apparaît pas dans la liste des apps de position fictive | Elle déclare `ACCESS_MOCK_LOCATION` dans le manifest, ce qui suffit normalement ; réinstaller après build si besoin. |

## Stack technique

- **Kotlin** + **Jetpack Compose** (Material 3)
- Services **foreground** (`SenderService`, `ReceiverService`)
- Réseau **UDP** (broadcast / écoute), protocole JSON minimal
- minSdk 33 · targetSdk 36 · compileSdk 36

## Structure du projet

```
app/src/main/java/fr/locationsender/
├── MainActivity.kt          # Navigation : choix du rôle / écrans / réglages
├── MainViewModel.kt         # État UI + persistance (rôle, port, facteur)
├── core/Bus.kt              # État partagé services ↔ UI (StateFlow)
├── net/
│   ├── NetUtils.kt          # Adresses de broadcast
│   └── Protocol.kt          # Encodage/décodage des paquets de position
├── service/
│   ├── SenderService.kt     # Lecture GPS + diffusion broadcast
│   ├── ReceiverService.kt   # Écoute + injection mock (avec ramp de vitesse)
│   └── Notifications.kt     # Canal et notifications foreground
├── ui/                      # Écrans Compose + thème
└── util/Perms.kt            # Permissions de localisation
```

## Avertissement

La position fictive (mock location) est une fonctionnalité destinée au
**développement et aux tests**. Utilisez cette application de manière
responsable et conforme aux conditions d'utilisation des services tiers.

## Licence

À définir — ajoutez un fichier `LICENSE` (par exemple MIT) avant publication si
vous souhaitez autoriser la réutilisation.
