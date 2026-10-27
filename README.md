# EDplayer

Reproductor de música para Android, minimalista en blanco y negro. Pensado para bibliotecas grandes de FLAC y MP3.

## Qué hace
- **Todo en aleatorio**: un botón grande reproduce toda tu biblioteca mezclada.
- **Álbumes**: en lista o en cuadrícula pequeña, mediana o grande (botón junto a las pestañas). Tocas uno y eliges **Reproducir** o **Aleatorio**, o tocas una canción para empezar por ella.
- **Artistas**: lista de artistas (usa la etiqueta *Album Artist* si existe); cada uno con sus álbumes y botones de reproducir/aleatorio.
- **Canciones**: lista de todas las canciones por orden alfabético.
- **Favoritas**: toca ♡ en el reproductor o "Añadir a favoritas" en el menú ⋮ de cualquier canción.
- **Gestos en el reproductor**: desliza ← siguiente, → anterior, ↑ abre el álbum, ↓ vuelve a la pantalla principal.
- **Portadas de internet**: si un disco no tiene portada, se busca en iTunes y Deezer y se guarda en el móvil (se puede desactivar en Ajustes; en el menú ⋮ de un álbum puedes forzar la búsqueda).
- **Portada con brillo**: en el reproductor, la portada irradia sus propios colores.
- **Editar etiquetas**: menú ⋮ de una canción → "Editar información"; ⋮ en un álbum o artista para editarlo, renombrarlo o unirlo con otro. Los artistas se agrupan ignorando mayúsculas y signos ("AC/DC" = "AC-DC"). Los cambios se guardan en la app, no en los archivos.
- **Cola**: en el reproductor, el icono de arriba a la derecha muestra lo que va a sonar. Puedes saltar a una canción, quitarla o reordenarla arrastrando (con aleatorio desactivado).
- **Playlists**: crea las tuyas, añade canciones o álbumes enteros desde el menú ⋮, y reordénalas arrastrando ≡. Se pueden renombrar y borrar.
- **Buscador**: filtra por álbum, artista o canción.
- **Gapless**: la siguiente canción se carga y decodifica mientras suena la actual, así que no hay silencio entre pistas (ideal para discos en directo o conceptuales). En MP3 se quita el relleno del codificador (LAME/iTunes); FLAC no lleva relleno.
- Sigue sonando con la pantalla apagada, con notificación y controles en la pantalla de bloqueo, auriculares y Bluetooth.
- Se pausa sola al desconectar los auriculares y cuando entra una llamada.

Requiere Android 8.1 o superior.

## Cómo conseguir el APK

### Opción A: GitHub (sin instalar nada)
1. Crea un repositorio en GitHub y sube esta carpeta.
2. Ve a la pestaña **Actions**; el flujo "Compilar APK" se ejecuta solo (unos 5 minutos).
3. Abre la ejecución terminada y descarga **EDplayer-apk** (es un zip con el `.apk` dentro).
4. Pasa el `.apk` al móvil, ábrelo y acepta "instalar apps de origen desconocido".

### Opción B: Android Studio
Abre la carpeta con Android Studio, espera a que sincronice y pulsa ▶ con el móvil conectado.

## Dónde poner la música
Por defecto se lee todo el móvil. Con el icono de ajustes (⚙) de la pantalla principal puedes elegir una o varias carpetas concretas (se incluyen sus subcarpetas).
Cualquier carpeta del almacenamiento del móvil o de la tarjeta SD (por ejemplo `Music/`). La app usa el índice de música de Android; si acabas de copiar archivos y no aparecen, pulsa ⟳ en la pantalla principal.
Las carátulas salen de la imagen incrustada en los archivos (o de `cover.jpg` / `folder.jpg` en la carpeta del álbum).

## Estructura técnica
- Kotlin + Jetpack Compose
- AndroidX Media3 / ExoPlayer, con `MediaSessionService` para reproducir en segundo plano
- `MusicLibrary.kt`: lectura de MediaStore y agrupación por álbum
- `PlaybackService.kt`: el reproductor (gapless, foco de audio, notificación)
- `PlaylistStore.kt`: guarda favoritas y playlists (JSON interno)
- `MainViewModel.kt`: estado y acciones
- `MainActivity.kt`: toda la interfaz
