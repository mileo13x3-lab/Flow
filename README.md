# Private Testsignierung

Der bisherige Schlüssel wird nicht veröffentlicht. Die vorhandene Build-Konfiguration
erwartet ihn lokal unter `signing/codepool-test.keystore`. Diese Datei ist per
`.gitignore` ausgeschlossen. Die bestehende Testkonfiguration verwendet den
Android-Debug-Alias und die Android-Debug-Passwörter; dies ist keine Produktionssignierung.

Für ein Update der vorhandenen Mr-M-Flow-Installation muss der bisherige Schlüssel
verwendet werden. Ein neu erzeugter Schlüssel erlaubt kein signaturgleiches Update.

GitHub Actions benötigt eine gesonderte Einrichtung, die den Schlüssel aus einem
Repository-Secret erst während des Builds bereitstellt. Diese Einrichtung wurde
nicht durchgeführt. Keystore-Dateien niemals ins Repository einchecken.
