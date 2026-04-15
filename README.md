# Player

Player - видеоплеер на Kotlin/JVM с direct FFmpeg backend, OpenGL-rendering и OpenAL audio output.

## Возможности

- Декодирование видео через FFmpeg presets.
- Воспроизведение звука через OpenAL.
- Синхронизация видео по audio clock, если в файле есть audio stream.
- Segment cache для keyframe index и ускорения seek/prefetch-логики.
- RGB fallback rendering по умолчанию.
- Experimental YUV shader rendering через `--native-yuv`.
- Диагностические режимы `--debug-sync` и `--debug-video`.

## Сборка

Требуется JDK 17+ для запуска приложения. Gradle wrapper уже включен в репозиторий.

```powershell
.\gradlew.bat build
```

Fat jar будет создан здесь:

```text
build\libs\decoder-1.1.0.jar
```

## Запуск

```powershell
java -jar build\libs\decoder-1.1.0.jar "C:\path\to\video.mp4"
```

Опции:

```text
--no-cache              отключить segment cache
--cache-dir <path>      задать директорию кеша
--cache-size-mb <n>     лимит кеша в MB
--audio-device default  использовать default OpenAL device
--native-yuv            включить experimental YUV shader rendering
--debug-sync            логировать sync/queue diagnostics
--debug-video           логировать diagnostics первого decoded video frame
--help                  показать справку
```

Горячие клавиши:

```text
Space       pause/resume
Left/Right  seek -10/+10 секунд
Up/Down     playback speed +/- 0.25x
F           показать FPS
D           показать debug overlay
R           seek в начало
```

## Кеш

По умолчанию кеш создается в `.player-cache` рядом с корнем проекта. Если jar запускается из `build\libs`, приложение пытается найти корень проекта и использовать его `.player-cache`, а не папку рядом с jar.

Кеш сейчас хранит segment/keyframe index и metadata. Он не хранит полный RGB/YUV cache кадров, потому что такой кеш быстро занимает очень много места.

## Лицензия

Этот проект распространяется по custom non-commercial attribution license. Код можно просматривать, собирать, запускать, изменять, форкать и распространять для некоммерческого использования при условии обязательного упоминания автора.

Если ваш код, проект, статья, видео, публикация или производная работа основаны на этом проекте или используют его части, необходимо явно указать автора/правообладателя: `bat1set` и ссылку на оригинальный репозиторий.

Любое коммерческое использование, продажа, платное распространение, монетизация, sublicensing, paid service, paid product или иное использование для получения прибыли допускается только после предварительного письменного разрешения правообладателя.

Полный текст условий находится в [LICENSE](LICENSE).
