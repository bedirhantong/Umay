# Umay

[![CI](https://github.com/bedirhantong/Umay/actions/workflows/ci.yml/badge.svg)](https://github.com/bedirhantong/Umay/actions/workflows/ci.yml)

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

Bina içinde GPS çalışmaz. Konum, birbirini tamamlayan iki kaynaktan ve
binanın haritasından bulunur:

- **Adım sayarak konum takibi (PDR):** Adımlar ivmeölçerden, yön yalnızca
  jiroskoptan alınır. Pusula (manyetometre) bilerek kullanılmaz, çünkü
  binadaki çelik ve kablolar onu her koridorda farklı biçimde saptırır. Adım
  uzunluğu her kişi için ayrıca ölçülür.
- **Bluetooth parmak izi:** Harita çıkarılırken isim verilen her noktada
  çevredeki Bluetooth işaretçilerinin (beacon) sinyal güçleri kaydedilir.
  Yönlendirme sırasında güvenilir bir eşleşme olunca konum tahmini o noktaya
  doğru çekilir, böylece adım saymanın zamanla biriken sapması giderilir.
- **Koridorlara oturtma (parçacık filtresi):** Konum tek bir nokta olarak
  değil, haritadaki koridorlar üzerinde yürüyen yüzlerce olası tahmin olarak
  izlenir. Koridorun gitmediği bir yöne atılan adımla uyuşmayan tahminler
  elenir, böylece tahmin duvarlardan geçemez ve kullanıcının yola hangi yöne
  bakarak başladığı ilk birkaç adımdan kendiliğinden anlaşılır. Yaklaşım,
  görme engelliler için geliştirilen açık kaynak
  [HULOP/NavCog](https://github.com/hulop) projesininkine dayanır.

Bir binanın haritası bir kez, **Beacon Tarayıcı** ekranıyla binada yürüyerek
çıkarılır: kayıt başlatılır, geçilen yerlere isim verilir, kapılar,
merdivenler, asansörler ve rampalar işaretlenir. Haritalar `.umay.json`
dosyası olarak cihazlar arasında dışa ve içe aktarılabilir.
Kaydedilmiş bir haritadaki hatalar (yanlış yazılmış bir ad, yanlış tür,
fazladan bir yer, duvardan geçen bir bağlantı, duvar haritasındaki bir leke)
binayı yeniden yürümeden **Dosyalar → Düzenle** ile düzeltilir.

Haritayı çıkaran kişinin telefonu ARCore'u destekliyorsa harita **kamerayla**
çıkarılabilir; robot süpürgelerin evi haritalamasına benzer. Telefon göğüs
hizasında, kamera önüne bakarken yürünür: yürünen yol adım saymak yerine
kameranın hareket takibinden santimetre düzeyinde ölçülür, köşeler haritaya
kendiliğinden eklenir. Telefon derinlik ölçebiliyorsa duvarlar da ölçülür ve
yürüdükçe ekranda 2D bir kat planı oluşur; bu plan haritayla birlikte
kaydedilir ve yönlendirme ekranında gösterilir. Kamera görüntüsü hiçbir yere
kaydedilmez. Yönlendirilen kişinin telefonunda kamera ya da ARCore gerekmez.

Telefonda basınç sensörü (barometre) varsa kat değişimi merdivende de
asansörde de hava basıncından kendiliğinden algılanır. Yoksa merdivende
adımlardan anlaşılır, asansörde bir düğmeyle onaylanır.

### Modüller

| Modül | İçerik |
|---|---|
| `core-model` | Saf Kotlin veri modeli: bina haritaları, konumlar, uyarılar, saha kaydı olayları, JSON |
| `positioning` | Adım algılama, adım sayarak konum takibi, Bluetooth tarama ve parmak izi eşleştirme, koridor grafiği üzerinde parçacık filtresi, saha kaydını yeniden oynatma, barometre |
| `perception` | CameraX ve TensorFlow Lite (EfficientDet-Lite0) ile insan algılama, mesafe ve yön tahmini |
| `orchestrator` | Katlar arası A* rota bulma, adım adım yönergeler, engel uyarıları, rotayı yeniden hesaplama |
| `feedback` | Metin okuma (ses seçimi, ses odağı yönetimi) ve titreşim desenleri |
| `cloud` | Uzaktaki harita kaynağının arayüzü, bağlantı durumu takibi ve kurum sunucusu istemcisi |
| `cloud-firebase` | Play Store sürümünün paylaşımlı harita havuzu (Firestore, anonim kimlik doğrulama) — yalnızca Firebase'in ücretsiz Spark katmanı kullanılır, Cloud Storage kullanılmaz |
| `server` | Kurum harita sunucusu: kurumun kendi ağında çalışır, haritaları dağıtır ([docs/SUNUCU.md](docs/SUNUCU.md)) |
| `app` | Compose arayüzü (MVVM, Hilt), arka planda çalışan navigasyon oturumu, harita çıkarma, saha kaydı |

## Derleme

Gereksinimler: JDK 17 ya da daha yeni bir sürüm (`core-model` için JDK 11 de
kullanılır) ve 36 platformu yüklü Android SDK.

Firebase yapılandırması (`app/google-services.json`) bu depoda yoktur. Derlemek
için örnek dosyayı kopyalayın:

```sh
cp app/google-services.json.example app/google-services.json
```

Bu örnek dosyayla uygulamanın tamamı derlenir ve çalışır; yalnızca paylaşımlı
harita havuzu bağlanamaz. Havuzu denemek için kendi Firebase projenizi
oluşturup ondan indirdiğiniz dosyayı kullanın ve `firestore.rules` kurallarını
o projeye yükleyin.

Uygulamanın iki dağıtımı vardır:

- **standard:** Play Store'daki sürüm. Paylaşımlı harita havuzu Firebase
  üzerinde çalışır.
- **institution (kurum sürümü):** Bir kurumun kendi binası için. Hiçbir veri
  cihazdan çıkmaz: APK'da Firebase yoktur, uygulamanın internet izni yoktur,
  yedekleme kapalıdır. Haritalar telefonlar arasında dosyayla aktarılır.
  Ayrıntılar [docs/GIZLILIK.md](docs/GIZLILIK.md) dosyasında. Kurum sürümü
  `google-services.json` gerektirmez ve "Umay Kurum" adıyla, Play Store
  sürümünün yanına ayrı bir uygulama olarak kurulur.

```sh
./gradlew :app:assembleStandardDebug        # debug APK
./gradlew :app:installStandardDebug         # bağlı cihaza yükle
./gradlew :app:assembleInstitutionRelease   # kurum sürümü
```

Testler, lint ve kurum sürümünün gizlilik denetimi:

```sh
./gradlew :core-model:test :positioning:testDebugUnitTest :perception:testDebugUnitTest \
          :feedback:testDebugUnitTest :orchestrator:testDebugUnitTest \
          :cloud:testDebugUnitTest :cloud-firebase:testDebugUnitTest :server:test \
          :app:testStandardDebugUnitTest \
          :app:verifyInstitutionReleasePrivacy :app:lintStandardDebug :app:lintInstitutionDebug
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

Telefonsuz test için `simulation` modülü var. Sanal bir ofiste, binayı
bilmeyen ve yalnızca uygulamanın söylediklerine göre yürüyen bir kör
kullanıcıyı canlandırır: konum ve yönlendirme kodu uygulamanınkinin aynısı,
cümleler `strings.xml`'den. Yürüyüşler bir sayfada izlenir: gerçek yol,
uygulamanın tahmini, parçacık bulutu, söylenenler, konum hatası. Sayfa
internete hiçbir istek yapmaz.

```sh
# Sanal ofisteki yürüyüşler: simulation/build/reports/walks/sanal-ofis.html
./gradlew :simulation:testDebugUnitTest --tests '*WalkReportsTest*'

# Kendi saha kayıtların: simulation/build/reports/walks/saha-kayitlari.html
UMAY_FIELD_LOGS=/kayitlarin/klasoru ./gradlew :simulation:testDebugUnitTest --tests '*FieldLogRecordingTest*'
```

Her pull request'te aynı testler, lint ve debug derlemesi GitHub Actions'ta
otomatik çalışır.

## Bir binada kurulum

Beacon seçimi ve yerleşimi, haritanın çıkarılması, denetlenmesi ve
dağıtılması [docs/KURULUM.md](docs/KURULUM.md) dosyasında.

## Yol haritası

Umay'ın hedefi ve oraya hangi sırayla gidileceği [docs/YOL_HARITASI.md](docs/YOL_HARITASI.md)
dosyasında. Hangi işin bittiği ve sıradakinin ne olduğu da orada takip edilir.

## Katkıda bulunmak

Hata bildirimleri (issue) ve pull request'ler memnuniyetle karşılanır.
Nereden başlayacağınız, kod düzeni ve pull request süreci için
[CONTRIBUTING.md](CONTRIBUTING.md) dosyasına bakın.

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
