# Clint Tasker Plugin

Plugin d'actions Tasker qui reprend le moteur de détection média de **Clint Browser**
(m3u8/HLS, mpd/DASH, mp4, webm, mp3…) pour trouver la vidéo d'une URL et la télécharger.

## Comment ça marche
Clint détecte les médias en observant chaque requête réseau de sa WebView (`shouldInterceptRequest`).
Le plugin fait pareil : l'URL est d'abord testée directement (si c'est déjà un mp4/m3u8…),
sinon elle est chargée dans une **WebView invisible**, une tentative de lecture automatique est
lancée (clic sur les boutons play, `video.play()`), et chaque requête passe dans le détecteur
(extensions, Content-Type, magic bytes, parsing des master playlists HLS/DASH, anti-spam de segments).

## Actions Tasker (Plugin → Clint Tasker Plugin)
**Clint : détecter vidéo** — variables de sortie :
`%clint_count`, `%clint_best_url`, `%clint_best_format`, `%clint_best_quality`,
`%clint_best_audio_url`, `%clint_best_headers` (une ligne `Clé: Valeur`), `%clint_urls` (une URL par ligne),
`%clint_json` (toutes les pistes détectées avec headers).

**Clint : télécharger vidéo** — détecte puis télécharge la meilleure (ou la plus basse) qualité.
Sorties : `%clint_file`, `%clint_audio_file`, `%clint_size`, `%clint_source_url`.
Sans dossier, le fichier va dans `Téléchargements/ClintTasker/` (aucune permission requise).

⚠ Dans l'action Tasker, mettez le champ **Délai (Timeout)** à 0 ou à une grande valeur,
sinon Tasker coupe l'action avant la fin du téléchargement.

## Limites connues
- DASH (.mpd) : détecté, mais **pas téléchargé** (il faudrait reprendre `DashSegmentResolver` + `MediaRemuxer` de Clint).
- HLS : les segments sont concaténés en `.ts` (ou `.mp4` fragmenté si fMP4), AES-128 géré, SAMPLE-AES non.
  La conversion TS→MP4 de Clint n'est pas incluse.
- HLS avec audio séparé : l'audio est téléchargé dans un second fichier (`*_audio.m4a`), non fusionné.
- Flux live : refusés. Pages protégées par DRM/blob/MSE sans URL réseau : non détectables.
- Pas de détection des métadonnées (durée/résolution) des fichiers directs (sondes MediaExtractor de Clint retirées).

## Compiler (sans PC)
1. Poussez ce dossier dans un dépôt GitHub.
2. Onglet **Actions → Build APK → Run workflow**.
3. Téléchargez l'APK dans les *Artifacts*, installez-le, puis ouvrez Tasker → Action → Plugin.

Ou avec Android Studio : ouvrir le dossier, *Build → Build APK*.

> Si la synchro Gradle échoue sur `com.joaomgcd:taskerpluginlibrary`, vérifiez la dernière version/coordonnée
> sur https://github.com/joaomgcd/TaskerPluginLibrary et ajustez `app/build.gradle.kts`.

Licence : GPL-3.0 (voir `LICENSE` et `ATTRIBUTION.md`).
# ClintTaskerPlugin
