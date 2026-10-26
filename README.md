# Casa (app Android)

Un'unica app per tutta la casa, con **una sola barra in basso, per tipo di dispositivo**:

| Voce | Cosa fa |
|---|---|
| **Tutti** | l'elenco di tutti i dispositivi (pagina del relay) |
| **Plancia** | la plancia del relay |
| **Termostati** | uno solo: lo apre; più di uno: pannello con tutti, con stato e temperatura |
| **Clima** | i condizionatori via relay (Wi-Fi) e via Bluetooth nello stesso pannello |
| **Accessi** | cancelli e garage |
| **Altro** | Sensori, Ospiti, Scorciatoie, Condizionatori Bluetooth, Personalizza |

Le voci senza dispositivi non compaiono. Dentro la pagina di un dispositivo
basta toccare di nuovo la sua voce per passare a un altro dello stesso tipo.
La barra del relay dentro l'app è nascosta (nel browser e per gli ospiti
resta com'è); la barra della pagina del dispositivo (Home, Avanzate…) è del
firmware e resta.

## Pagine del relay

- È una WebView: non serve Chrome, quindi funziona anche su autoradio e
  tablet dove Chrome non installa le app (niente pagina "Installa l'app").
- Il primo accesso si fa con la password come sempre; la sessione resta.
- **Indietro** torna alla pagina precedente; sulla prima l'app va in
  secondo piano (non si chiude).
- Gli inviti degli ospiti (**Condividi**) aprono la condivisione di Android.

## Scorciatoie (Accessi)

1. Nella scheda Casa entra nei dispositivi dei cancelli (se non l'hai già fatto).
2. **Altro → Scorciatoie** → **Apri come**: il nome che compare nello storico
   (si prende da solo da quello della pagina Accessi).
3. **Accesso 1, 2, 3**: scegli quale pulsante (garage, cancello,
   cancellino...) va su ciascuno.
4. **Riquadri nelle impostazioni rapide**: su Android 13+ l'app chiede di
   aggiungerli; su quelli più vecchi: impostazioni rapide → matita →
   trascina "Accesso 1/2/3".
5. **Widget nella schermata Home**: una fila con i pulsanti scelti.

Un tocco apre: il riquadro mostra "invio…" e poi "inviato ✓" (o il motivo,
per esempio "già in corso"). A telefono bloccato Android chiede prima di
sbloccarlo. Le aperture passano dal relay con la sessione della scheda Casa,
come dalla pagina Accessi (canale **relay** nello storico).

## Clima BT

Come prima (vedi sotto, "Clima BT in dettaglio"). La scheda chiede il
permesso "Dispositivi nelle vicinanze" solo quando la apri.

## Compilare

Con GitHub: **Actions** → **Compila APK** (parte da sola a ogni modifica) →
in fondo all'esecuzione scarica **Casa-apk** e installa `Casa.apk`.

La chiave di firma è fissa (`app/casa.keystore`, password nel file
`app/build.gradle.kts`): ogni APK si installa sopra il precedente. Tieni il
repository **privato** e una copia della chiave fuori da GitHub: senza la
stessa chiave Android non accetta gli aggiornamenti.

## Clima BT in dettaglio

- All'apertura si collega all'ultimo condizionatore, senza elenchi.
- Prima un collegamento diretto (rapido); se non risponde entro 10 s,
  Android continua a cercarlo da solo e si collega appena lo sente.
- Se il collegamento cade riparte da sola; spegnendo il Bluetooth del
  telefono mostra "Bluetooth spento" e si ricollega quando lo riaccendi.
- Più condizionatori: tocca il nome in alto per l'elenco; ognuno col suo PIN.
- Impostazioni: Wi-Fi, modalità di collegamento, info, PIN, nome,
  "Dimentica questo condizionatore".

## File

```
app/src/main/java/me/synology/davidevinavil/casa/
  MainActivity.kt     tre schede
  Web.kt              scheda Casa (WebView del relay)
  Relay.kt            richieste al relay per gli Accessi
  Shortcuts.kt        riquadri delle impostazioni rapide e widget
  ShortcutsScreen.kt  scheda Scorciatoie
  Ble.kt              Clima BT: collegamento e ricollegamento automatico
  Model.kt            Clima BT: protocollo
  Store.kt            impostazioni salvate
  App.kt, Control.kt  Clima BT: schermate
  Theme.kt            colori e icone
```
