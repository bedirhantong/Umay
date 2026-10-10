# Umay'a katkıda bulunmak

Umay, görme engelli kişilerin bina içinde bağımsız yol bulabilmesi için
geliştirilen açık kaynak bir Android uygulamasıdır. Kod, test, erişilebilirlik
geri bildirimi, saha denemesi ya da çeviri: her katkı değerlidir.

## Nereden başlamalı

- [`good first issue`](https://github.com/bedirhantong/Umay/labels/good%20first%20issue)
  etiketli issue'lar yeni başlayanlar için seçilmiştir.
- Büyük bir değişiklik düşünüyorsanız önce bir issue açıp yaklaşımı konuşalım;
  boşa emek harcanmasın.
- Görme engelliyseniz ya da ekran okuyucu kullanıyorsanız, uygulamayı
  denemeniz ve deneyiminizi bir issue ile paylaşmanız en değerli katkılardan
  biridir.

## Geliştirme ortamı

1. Depoyu klonlayın ve Firebase örnek yapılandırmasını kopyalayın:
   ```sh
   cp app/google-services.json.example app/google-services.json
   ```
2. Android Studio ile açın (JDK 17+, Android SDK 36).
3. Testleri ve lint'i çalıştırın:
   ```sh
   ./gradlew :core-model:test :positioning:testDebugUnitTest :perception:testDebugUnitTest \
             :orchestrator:testDebugUnitTest :feedback:testDebugUnitTest :cloud:testDebugUnitTest :cloud-firebase:testDebugUnitTest :server:test \
             :app:testStandardDebugUnitTest :app:verifyInstitutionReleasePrivacy \
             :app:lintStandardDebug :app:lintInstitutionDebug
   ```
   Uygulamanın iki dağıtımı var (`standard`, `institution`); farkları
   README'deki "Derleme" bölümünde.

Sensörler, Bluetooth ve kamera gerektiği için uygulamayı gerçek bir cihazda
deneyin. Performansı değerlendirirken `profile` derleme türünü kullanın; debug
derlemeleri gerçekte olduğundan çok daha yavaş çalışır.

## Kod düzeni

- **Mimari:** Her ekranın bir `@HiltViewModel`'i vardır. ViewModel'ler durumu
  `StateFlow` ile sunar, ekranlar `collectAsStateWithLifecycle` ile toplar.
  Bağımlılıklar arayüzler üzerinden `app/.../di/AppModule.kt` içinde bağlanır.
- **Test:** Yeni davranış JVM birim testiyle gelsin. Sahte bağımlılıklar
  `app/src/test/.../testing/Fakes.kt` içindedir.
- **Ana thread:** Disk, ağ ve ağır hesaplama ana thread'de yapılmaz. Debug
  derlemesinde StrictMode ihlalleri logcat'e düşer.
- **Metinler:** Kullanıcının gördüğü ya da duyduğu her metin
  `strings.xml`'dedir; kodda sabit metin olmaz.
- **Yorumlar:** Kodun *ne* yaptığını değil, *neden* öyle yapıldığını açıklayın.

## Erişilebilirlik

Bu uygulamanın asıl kullanıcıları ekranı görmez. Arayüze dokunan her değişiklik:

- TalkBack açıkken denenmiş olmalı: her kontrolün anlamlı bir adı olmalı ve
  hiçbir şey iki kez okunmamalı.
- En az 48 dp dokunma alanı bırakmalı.
- Uygulamanın kendi sesiyle TalkBack'i üst üste konuşturmamalı
  (`rememberIsScreenReaderOn`).

## Dallar ve sürümler

- `development`: günlük geliştirme dalı. Tüm pull request'ler buraya açılır.
- `main`: yalnızca yayınlanan sürümler. `development`, bir sürüm çıkarılırken
  `main`'e birleştirilir ve o commit `v1.2.0` gibi bir etiketle işaretlenir.
- Dal adları işe göre: `feat/…`, `fix/…`, `perf/…`, `refactor/…`, `chore/…`.

## Pull request

- Pull request'i `development` dalına açın.
- Küçük ve tek konulu tutun; açıklamada *neden* yapıldığını anlatın.
- Testler, lint ve derleme her PR'da GitHub Actions'ta otomatik çalışır ve
  geçmelidir.
- Yönlendirme, konum ya da sesli yönergeleri etkileyen bir değişiklikte gerçek
  bir cihazda nasıl denediğinizi yazın.
- Katkılarınız projenin lisansı olan [GPL-3.0](LICENSE) ile yayınlanır.

## Güvenlik

Umay bir yardımcıdır; beyaz bastonun ya da rehber köpeğin yerini tutmaz. Bir
kullanıcıyı yanlış yönlendirebilecek bir hata bulursanız lütfen öncelikli
olarak bildirin.
