# Umay'da veri nerede durur

Bu belge, Umay'ı kendi binasında kullanmayı düşünen kurumların bilgi işlem ve
kişisel veri sorumluları içindir. Uygulamanın hangi veriyi işlediğini, nerede
tuttuğunu, ne zaman sildiğini ve cihazdan neyin çıkıp neyin çıkmadığını anlatır.

Buradaki her cümle koddan doğrulanabilir; ilgili dosyalar parantez içinde
verilmiştir. Kod ile bu belge çelişirse hata kabul edilir ve düzeltilir.

Son güncelleme: 10 Ekim 2026

---

## İki dağıtım

Umay iki ayrı uygulama olarak derlenir:

| | **Standart** (Play Store) | **Kurum sürümü** ("Umay Kurum") |
|---|---|---|
| Uygulama kimliği | `com.appvalence.umay` | `com.appvalence.umay.kurum` |
| İnternet izni | Var (yalnızca paylaşımlı harita havuzu için) | **Yok** |
| Firebase | Var (paylaşımlı harita havuzu) | **Yok**, APK'da bulunmaz |
| Android yedeklemesi | Haritalar ve ayarlar yedeklenebilir | **Kapalı** (bulut ve cihazdan cihaza) |
| Harita aktarımı | Dosya ya da paylaşımlı havuz | Yalnızca dosya |

Kurum sürümünün güvenceleri derleme sırasında denetlenir: birleşik manifest
internet izni isterse, yedeklemeye izin verirse ya da APK'ya bir Firebase
hizmeti girerse derleme başarısız olur (`VerifyInstitutionPrivacy`,
`app/build.gradle.kts`). İnternet izni olmayan bir uygulama Android
tarafından ağa hiç çıkarılmaz; buna ek olarak kurum sürümü hiçbir sertifika
otoritesine güvenmez ve şifresiz bağlantıya izin vermez
(`app/src/institution/`).

Bir kurumun kendi ağında çalışan harita sunucusu henüz yoktur (bkz.
`docs/YOL_HARITASI.md`, 1.5). Eklendiğinde kurum sürümü yalnızca o sunucunun
adresine bağlanabilecek şekilde derlenecektir.

---

## İzinler ve neden gerektikleri

| İzin | Ne için | Veriye ne olur |
|---|---|---|
| Kamera | Yönlendirmede önündeki insanları algılamak, tabela okumak; harita çıkarırken kamerayla ölçmek | Görüntü bellekte işlenip atılır. Kaydedilmez, ekranda gösterilmez, gönderilmez. |
| Konum (yakın ve yaklaşık) | Android, Bluetooth işaretçilerini taramak için bunu şart koşar | GPS kullanılmaz. Bina içi konum adım sensörleri ve Bluetooth sinyallerinden cihazda hesaplanır. |
| Bluetooth tarama | Binadaki işaretçilerin (beacon) sinyal gücünü ölçmek | Bellekte kullanılır. Harita çıkarırken haritaya, saha kaydı açıksa kayda yazılır. |
| Mikrofon | Yalnızca "Bir şey sor" düğmesine basılınca soruyu dinlemek | Ses cihazda metne çevrilir, saklanmaz (aşağıya bakın). |
| Bildirim, ön plan hizmeti | Ekran kapalıyken yönlendirmeye devam etmek | Veri işlenmez. |
| Titreşim | Yönergeleri titreşimle bildirmek | Veri işlenmez. |
| İnternet (yalnızca standart) | Paylaşımlı harita havuzu | Aşağıya bakın. |

Telefon durumu ve harici depolama izinleri istenmez. Bir kütüphanenin eski
manifesti yüzünden kendiliğinden eklenen bu izinler açıkça kaldırılmıştır
(`app/src/main/AndroidManifest.xml`).

---

## Cihazda saklanan veriler

Hepsi uygulamanın kendi özel klasöründedir; başka uygulamalar okuyamaz.
Uygulama kaldırılınca hepsi silinir.

| Veri | İçerik | Yer | Silme |
|---|---|---|---|
| Bina haritaları | Yer adları, bina içi koordinatlar, kat bilgisi, isimli yerlerde ve yol boyunca birkaç metrede bir kaydedilen beacon sinyal güçleri, kamerayla çıkarıldıysa duvar haritası | `files/buildings/` | Dosyalar ekranı |
| Ayarlar | Konuşma hızı, adım uzunluğu, ekranın açık kalması gibi tercihler | Paylaşılan tercihler | Uygulamayı kaldırmak |
| Saha kaydı (varsayılan kapalı) | Yönlendirme sırasında bina içi konum, adımlar, sinyal gücü, hava basıncı, verilen yönergeler, işaretlenen gerçek konumlar. Görüntü ve ses yoktur. | `files/field-logs/`, en fazla 50 dosya, en eskisi kendiliğinden silinir | Dosyalar ekranı |
| Bitmemiş harita kaydı | Harita çıkarılırken yürünen yol, isimli yerler, okunan beacon sinyalleri, duvar haritası; uygulama kayıt sırasında kapanırsa geri gelsin diye | `files/survey-draft/`, yedeğe girmez | Haritalama ekranında kaydı temizlemek |
| Çökme kayıtları | Uygulama çökerse hata izi ve o anki yönlendirmenin başlangıç ve hedef noktası | `files/crash-logs/` | Dosyalar ekranı |
| Dışa aktarılan harita kopyası | Paylaşılmak üzere hazırlanan `.umay.json` | Önbellek klasörü | Android önbelleği temizledikçe |

Saha kayıtları, çökme kayıtları ve haritalar cihazdan yalnızca kullanıcı
Android'in paylaşma ekranıyla kendisi gönderirse çıkar.

---

## Cihazda çalışan ve dışarı çıkmayan işlemler

- **Konum hesabı.** Adım sayma, yön, Bluetooth parmak izi eşleştirme ve
  parçacık filtresi tamamen cihazdadır (`positioning` modülü).
- **İnsan algılama.** TensorFlow Lite ve uygulamanın içinde gelen
  EfficientDet-Lite0 modeliyle cihazda çalışır (`perception` modülü).
- **Rota ve yönergeler.** Cihazda hesaplanır (`orchestrator` modülü).
- **"Bir şey sor".** Android 12 ve sonrasında yalnızca telefonun cihaz içi
  konuşma tanıyıcısı kullanılır. Telefonda böyle bir tanıyıcı ya da Türkçe
  paketi yoksa özellik çalışmaz ve nedenini söyler; internet üzerinden
  tanımaya geçilmez. Android 13 ve sonrasında eksik Türkçe paketi tanıma
  hizmetinin kendi uygulamasınca indirilir; bu indirme kullanıcının sesini
  içermez. Android 11 ve öncesinde cihaz içi tanıyıcı arayüzü yoktur; istek
  "yalnızca çevrimdışı tanıma" işaretiyle gönderilir (Android'in belgelediği
  `EXTRA_PREFER_OFFLINE`). Tanınan metin yalnızca bina haritasında aranır ve
  atılır (`app/.../voice/SpeechToText.kt`).
- **Sesli yönergeler.** Yalnızca telefona yüklü, internet gerektirmeyen bir
  Türkçe ses seçilir. Konuşma motoru yine de internet üzerinden okuyan bir
  sese düşerse uygulama bunu söyler ve internetsiz Türkçe sesin yüklenmesini
  ister (`feedback/.../VoiceSelection.kt`).

---

## Google bileşenleri

Umay'ın kendisi hiçbir sunucuya veri göndermez. Aşağıdaki Google bileşenleri
uygulamanın içinde ya da yanında çalışır:

**ML Kit metin tanıma (her iki dağıtımda).** Tabela okumak için kullanılır.
Google'a göre görüntü ve sonuç tamamen cihazda işlenir, Google'a
gönderilmez; ancak ML Kit, API'lerin performansı ve kullanım sıklığıyla
ilgili ölçümleri Google'a gönderir ve zaman zaman model güncellemesi için
Google sunucularına bağlanır (developers.google.com/ml-kit/terms). Metin
tanıma modelini Google Play Hizmetleri indirir. Kurum sürümünde uygulamanın
internet izni olmadığından bu ölçümler uygulamadan gönderilemez; model
indirmesi Google Play Hizmetleri'nin kendi işidir ve kullanıcı verisi içermez.

**ARCore (yalnızca kamerayla harita çıkarmada).** Haritayı çıkaran kişi
"Kamerayla" modunu seçerse, kameranın hareket takibi ve derinlik ölçümü
Google'ın "Google Play Services for AR" uygulamasıyla yapılır. Umay, Google'ın
istediği bildirimi Ayarlar → Hakkında'da ve haritalama ekranında gösterir.
ARCore ayrı bir Google uygulaması olarak çalışır ve Google Gizlilik
Politikası'na tabidir; Umay'ın internet izninin olmaması onu kapsamaz.
Yönlendirilen kişinin telefonunda ARCore kullanılmaz. Hiçbir Google AR
bileşeni istemeyen bir kurum, haritayı çıkaracak telefona "Google Play
Services for AR" yüklemez; o zaman harita adım sayarak çıkarılır.

**Firebase (yalnızca standart dağıtım).** Paylaşımlı harita havuzu Cloud
Firestore ve Firebase Anonim Kimlik Doğrulama ile çalışır. Havuza bakmak ve
harita indirmek için giriş gerekmez. Kullanıcı bir haritayı "herkese aç"
dediğinde, onayından sonra şunlar yüklenir ve herkes tarafından okunabilir
olur: bina adı, kat ve nokta sayısı, dosya boyutu, güncelleme zamanı,
haritanın kendisi ve rastgele üretilmiş anonim bir kimlik. Yayın Dosyalar
ekranından kaldırılabilir (yalnızca yükleyen cihaz). Google, hizmeti sunarken
IP adresi gibi teknik verileri kendi politikasına göre işleyebilir.

**Telefonun kendi hizmetleri.** Konuşma tanıma ve metin okuma motorları
telefonda yüklü uygulamalardır (çoğu cihazda Google'ınkiler). Umay onları
yalnızca yukarıda anlatılan cihaz içi kipte kullanır.

**Android yedeklemesi (yalnızca standart dağıtım).** Telefonda Android
yedeklemesi açıksa haritalar ve ayarlar kullanıcının Google hesabındaki
yedeğe girebilir. Saha ve çökme kayıtları yedeğe girmez
(`app/src/main/res/xml/`). Kurum sürümünde yedekleme tamamen kapalıdır.

Umay reklam, kullanım analizi ya da uzaktan çökme raporlama hizmeti
kullanmaz.

---

## Kurum için kontrol listesi

- [ ] Kurum sürümünü kullanın (`./gradlew :app:assembleInstitutionRelease`)
  ve kurumun kendi anahtarıyla imzalayın.
- [ ] Kurulumdan sonra uygulamanın internet izni olmadığını doğrulayın.
  İnternet izni Android'in izinler ekranında görünmeyen bir izindir; bilgisayara
  bağlı telefonda
  `adb shell dumpsys package com.appvalence.umay.kurum | grep android.permission.INTERNET`
  hiçbir şey döndürmemelidir.
- [ ] Haritayı kamerayla çıkarmak istiyorsanız, yalnızca haritayı çıkaracak
  telefona "Google Play Services for AR" yükleyin; istemiyorsanız haritayı
  adım sayarak çıkarın.
- [ ] Kullanıcı telefonlarında Türkçe internetsiz konuşma sesinin ve
  (Bir şey sor için) cihaz içi Türkçe konuşma tanımanın yüklü olduğunu
  kontrol edin. Umay eksik olduğunda bunu kendisi söyler.
- [ ] Haritaları kurum içinde dosya olarak dağıtın (e-posta, MDM, USB). Bina
  ve yer adlarına kişisel bilgi yazmayın.
- [ ] Saha kaydını yalnızca test için açın; kayıt dosyaları konum geçmişi
  içerir.

---

## Play Store veri güvenliği formu için özet (standart dağıtım)

Geliştiricinin formu doldururken kontrol etmesi için önerilen beyan:

- **Toplanan veri:**
  - *Kullanıcı içeriği (diğer):* kullanıcı bir haritayı paylaşımlı havuzda
    yayınlarsa bina adı ve harita. İsteğe bağlı. Amaç: uygulama işlevi.
    Herkese açık olarak paylaşılır.
  - *Cihaz veya diğer kimlikler:* yayın için Firebase anonim kimliği.
    İsteğe bağlı (yalnızca yayınlayanlarda). Amaç: uygulama işlevi
    (yayını yalnızca sahibinin kaldırabilmesi).
  - *Uygulama bilgileri ve performans (tanılama):* ML Kit'in Google'a
    gönderdiği kullanım ve performans ölçümleri. Zorunlu. Amaç: analiz
    (Google'ın kendi SDK'sı için).
- **Toplanmayan:** konum, ses, fotoğraf ve video, kişisel bilgiler,
  kimlik bilgileri, finansal bilgiler. Kamera, mikrofon ve konum izinleri
  yalnızca cihazda işlenen veriler içindir.
- **Aktarım şifreli:** evet (Firebase ve ML Kit TLS kullanır).
- **Silme talebi:** yayın uygulamadan kaldırılabilir; ayrıca web sitesindeki
  veri silme sayfası.

ARCore için Google'ın istediği bildirim uygulamanın içindedir; mağaza
listesine eklemek de önerilir.

---

## Açık noktalar

- Web sitesindeki gizlilik politikası (`feat/website` dalı) bu belgeyle uyumlu
  hâle getirildi; uygulamanın bu sürümüyle birlikte yayınlanmalıdır, daha önce
  değil (anlattığı davranışlar bu sürümde geliyor).
- Android 11 ve öncesinde "yalnızca çevrimdışı" isteğine uymak telefondaki
  konuşma tanıma hizmetinin sorumluluğundadır; Umay bunu denetleyemez.
