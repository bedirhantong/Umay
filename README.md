# Umay

Umay, görme engelli kişiler için geliştirilmiş, bina içinde yol gösteren bir
Android uygulamasıdır. Kişiyi gitmek istediği yere sesli yönergeler ve her
biri farklı titreşim desenleriyle götürür. Kamerayla hemen önündeki insanlara
karşı uyarır. Merdiven ve asansör dahil katlar arasında da çalışır. Ekran
kapalıyken ve telefon cepteyken de yönlendirmeye devam eder.

Uygulamanın arayüzü ve sesli yönergeleri Türkçedir. Kullanıcının gördüğü ve
duyduğu tüm metinler kaynak dosyalarındadır, başka dillere çevrilmeye hazırdır.

> **Güvenlik:** Umay bir yardımcıdır, bir hareket aracı değildir. Beyaz baston
> ya da rehber köpeğin **yerini tutmaz**. Kamera yalnızca insanları algılar.
> Merdiven, kapı ve rampalar yalnızca binanın haritasını çıkaran kişi onları
> işaretlediyse bilinir. Konum tahmini birkaç metre sapabilir.

## Nasıl çalışır

Bina içinde GPS çalışmaz. Konum, birbirini tamamlayan iki kaynaktan bulunur:

- **Adım sayarak konum takibi (PDR):** Adımlar ivmeölçerden, yön yalnızca
  jiroskoptan alınır. Pusula (manyetometre) bilerek kullanılmaz, çünkü
  binadaki çelik ve kablolar onu her koridorda farklı biçimde saptırır. Adım
  uzunluğu her kişi için ayrıca ölçülür.
- **Bluetooth parmak izi:** Harita çıkarılırken isim verilen her noktada
  çevredeki Bluetooth işaretçilerinin (beacon) sinyal güçleri kaydedilir.
  Yönlendirme sırasında güvenilir bir eşleşme olunca konum o noktaya
  düzeltilir, böylece adım saymanın zamanla biriken sapması giderilir.

Bir binanın haritası bir kez, **Beacon Tarayıcı** ekranıyla binada yürüyerek
çıkarılır: kayıt başlatılır, geçilen yerlere isim verilir, kapılar,
merdivenler, asansörler ve rampalar işaretlenir. Haritalar `.umay.json`
dosyası olarak cihazlar arasında dışa ve içe aktarılabilir.

Telefonda basınç sensörü (barometre) varsa kat değişimi merdivende de
asansörde de hava basıncından kendiliğinden algılanır. Yoksa merdivende
adımlardan anlaşılır, asansörde bir düğmeyle onaylanır.

### Modüller

| Modül | İçerik |
|---|---|
| `core-model` | Saf Kotlin veri modeli: bina haritaları, konumlar, uyarılar, saha kaydı olayları, JSON |
| `positioning` | Adım algılama, adım sayarak konum takibi, Bluetooth tarama ve parmak izi eşleştirme, konum birleştirme, barometre |
| `perception` | CameraX ve TensorFlow Lite (EfficientDet-Lite0) ile insan algılama, mesafe ve yön tahmini |
| `orchestrator` | Katlar arası A* rota bulma, adım adım yönergeler, engel uyarıları, rotayı yeniden hesaplama |
| `feedback` | Metin okuma (ses seçimi, ses odağı yönetimi) ve titreşim desenleri |
| `app` | Compose arayüzü, arka planda çalışan navigasyon oturumu, kayıt, saha kaydı |

## Derleme

Gereksinimler: JDK 17 ya da daha yeni bir sürüm (`core-model` için JDK 11 de
kullanılır) ve 36 platformu yüklü Android SDK.

```sh
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:installDebug           # bağlı cihaza yükle
```

Testler ve lint:

```sh
./gradlew :core-model:test :positioning:test :perception:test \
          :feedback:test :orchestrator:test :app:testDebugUnitTest :app:lintDebug
```

Uygulamanın işe yaraması için gerçek bir cihaz gerekir: sensörler, Bluetooth
ve kamera. Emülatörde yalnızca arayüz görülebilir.

## Saha testi

**Dosyalar → Navigasyon sırasında kayıt tut** açıkken her yönlendirme bir
JSON Lines dosyasına kaydedilir. Dosyada şunlar bulunur:

- harita ve tüm ayar değerleri,
- adım sayma, parmak izi ve birleştirilmiş konumlar,
- beacon sinyal güçleri ve hava basıncı,
- okunan yönergeler ve kat değişimleri,
- "Buradayım" işaretleri: gerçek konum hatasını ölçmek için.

Tahmine dayanan ayar değerleri bu kayıtlarla gerçek yürüyüşlere göre
ayarlanır. Çökme kayıtları da cihazda tutulur. Kullanıcı paylaşmadıkça hiçbir
veri cihazdan çıkmaz.

## Katkıda bulunmak

Hata bildirimleri (issue) ve pull request'ler memnuniyetle karşılanır. Pull
request açmadan önce lütfen testleri ve lint'i çalıştırın. Yönergelerin
davranışını değiştiren bir değişiklikte, gerçek bir cihazda nasıl test
ettiğinizi de anlatın.

## Lisans

Umay özgür yazılımdır ve **GNU Genel Kamu Lisansı sürüm 3 (GPL-3.0)** ile
dağıtılır ([LICENSE](LICENSE)). Yazılımı kullanabilir, inceleyebilir,
değiştirebilir ve yeniden dağıtabilirsiniz. Dağıtılan her türev de aynı
lisansla ve kaynak koduyla birlikte yayınlanmalıdır.

Üçüncü taraf bileşenler kendi lisanslarını korur. Hepsi GPL-3.0 ile
uyumludur:

- **Apache Lisansı 2.0:** AndroidX ve Jetpack Compose, CameraX,
  TensorFlow Lite ve Task Library, EfficientDet-Lite0 modeli
  (`perception/src/main/assets/`), kotlinx.coroutines ve
  kotlinx.serialization.
