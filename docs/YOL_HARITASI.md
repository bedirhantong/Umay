# Umay yol haritası

Bu dosya Umay'ın nereye gittiğini ve oraya hangi sırayla varacağını tutar.
Yaşayan bir belgedir: bir iş bittiğinde kutusu işaretlenir, en alttaki
**İlerleme kaydı**'na tarihli bir satır eklenir. Yeni bir iş bu plana
uymuyorsa önce plan güncellenir.

Son güncelleme: 10 Ekim 2026

---

## Hedef

Görme engelli bir kişi, bir kurumun binasında (beacon'lı),
**hiçbir veriyi cihazdan veya kurumun kendi sisteminden dışarı çıkarmadan**
bir yerden başka bir yere kendi başına gidebilsin.

Ev (beacon'sız) ikinci senaryodur: Faz 0–5 kurum içindir, ev bunlar bittikten
sonra ele alınır.

Kullanıcının gözünden akış:

1. **Harita bir kez çıkarılır.** Gören biri binada telefonla yürür. Duvarlar,
   kapılar ve önemli yerler 2D bir kat planında belli olur (robot süpürgenin
   evi haritalaması gibi).
2. **Kullanıcı nerede olduğunu bildirir.** Bunun için iki yolu vardır:
   - Haritada işaretlenmiş yerlerden birini TalkBack ile seçer ("Salondayım").
   - Telefonun kamerasıyla etrafı gösterir, uygulama konumunu kendisi bulur.

   Kurumda beacon'lar konumu kabaca kendiliğinden de verir.
3. **Gideceği yeri seçer.** Seçimi TalkBack ile listeden ya da sesle yapar.
4. **İlerler.** Telefon göğüs hizasında, kamera önüne bakar. Uygulama sesli
   ve titreşimli yönergeler verir, önüne çıkan engellere karşı uyarır. Telefon
   cepteyken de adım ve beacon'larla yönlendirme sürer.

### Değişmez ilkeler

- **Veri dışarı çıkmaz.** Konum, kamera görüntüsü, ses ve harita cihazda ya da
  kurumun kendi sunucusunda kalır. Kurum sürümü yalnızca kurumun ağındaki
  adrese bağlanır.
- **Umay bir yardımcıdır, baston ya da rehber köpeğin yerini tutmaz.** Bu her
  ekranda ve yönergede böyle kalır.
- **Ölçülmeyen doğruluk iddia edilmez.** Her konum yöntemi sahada, "Buradayım"
  işaretli saha kayıtlarıyla ölçülür. Ayarlar tahminle değil kayıtlarla
  yapılır.
- **Önce erişilebilirlik.** Her ekran TalkBack ile tek başına kullanılabilir.
  Uygulamanın sesi TalkBack'le üst üste konuşmaz.
- **Kamera görüntüsü hiçbir yere kaydedilmez.** Yalnızca ölçüm (konum,
  derinlik) için anlık kullanılır.

---

## Bugünkü durum

| Alan | Durum | Nerede |
|---|---|---|
| Adım sayarak harita çıkarma, isimli yerler, beacon parmak izi | Çalışıyor | `main` |
| Graf üzerinde A* rota, sesli ve titreşimli yönergeler, katlar arası geçiş | Çalışıyor | `main` |
| Kamerayla insan algılama, tabela okuma, "Bir şey sor" | Çalışıyor | `main` |
| Ekran kapalıyken ve cepteyken yönlendirme | Çalışıyor | `main` |
| Koridor grafiği üzerinde parçacık filtresi (konum) | Yazıldı, **sahada ölçülmedi** | `feat/graph-particle-filter` |
| Saha kaydını eski ve yeni motorla yeniden oynatma | Yazıldı | `feat/graph-particle-filter` |
| Kamerayla (ARCore) harita çıkarma, 2D duvar haritası, köşe noktaları | Yazıldı, **telefonda hiç çalışmadı** | `feat/ar-survey` |
| Yer işaretleriyle (QR) konum bulma, "Kamerayla bul" | Yazıldı, **telefonda doğrulanmadı** | `feat/ar-survey` |
| Kamerayla engel uyarısı: insan ve eşyalar (sandalye, masa, bank…) | Yazıldı, **telefonda doğrulanmadı** | `feat/ar-survey` |
| 16 KB sayfa boyutu uyumu (LiteRT) | Yapıldı | `feat/ar-survey` |
| Açık alanda kestirme bağlantılar, duvara dayalı yönerge | Yazıldı, **sahada doğrulanmadı** | `feat/ar-survey` |
| Isı, pil ve telefonun tutuşuna göre kamera | Yazıldı, **telefonda doğrulanmadı** | `feat/ar-survey` |
| Kurum harita sunucusu | Yazıldı, HTTP testleri geçiyor | `feat/ar-survey` |
| Kapıların harita parçası olması | Yazıldı, **telefonda doğrulanmadı** | `feat/ar-survey` |
| Harita düzenleme | Yazıldı, **TalkBack ile denenmedi** | `feat/ar-survey` |
| Yol boyunca sık parmak izi, her taramanın filtreye kanıt olması | Yazıldı, **sahada ölçülmedi** | `feat/ar-survey` |
| Başlangıç noktasını beacon'larla önerme | Yazıldı, **sahada ölçülmedi** | `feat/ar-survey` |
| Harita kaydının uygulama kapanınca kaybolmaması | Yazıldı, **telefonda doğrulanmadı** | `feat/ar-survey` |
| Kurum sürümü (Firebase'siz, internet izinsiz, yedeklemesiz) | Yazıldı, derlemede denetleniyor | `feat/ar-survey` |
| Konuşma tanıma yalnızca cihazda, internetsiz ses | Yazıldı, **telefonda doğrulanmadı** | `feat/ar-survey` |

---

## Fazlar

Büyüklükler kabadır: **K** birkaç gün, **O** bir iki hafta, **B** birkaç
hafta ve üstü. "Bitti" ölçütü karşılanmadan kutu işaretlenmez. Kodu yazılmış
ama bitti ölçütü telefonda ya da sahada doğrulanmayı bekleyen işlerde kutu
boş kalır ve **Kod hazır** yazar; ne doğrulanacağı yanında belirtilir.

### Faz 0: Eldekini sağlamlaştır

Yeni bir şeye geçmeden, yazılmış ama doğrulanmamış işi gerçeğe bağla.

- [ ] **0.1 Release derlemesi** (K). R8 küçültmeli `assembleRelease`
  ARCore ve yeni serileştirilebilir sınıflarla derleniyor ve açılıyor.
  *Bitti:* imzasız release APK telefonda harita çıkarma ve yönlendirme
  ekranlarını açıyor.
- [ ] **0.2 Kamerayla harita çıkarmayı telefonda dene** (K). Bu, Faz 3 ve
  Faz 4'ün temeli.
  *Bitti:* bir koridor ve bir oda kamerayla haritalandı. Ekrandaki duvarlar
  gerçekle 20 cm içinde örtüşüyor, kamera kapatılıp açılınca yol kopmuyor.
  Sorunlar `ArCoreSurveyCamera` logcat'iyle raporlandı ve düzeltildi.
  Kamerayla haritalamanın denenmesi için beacon gerekmez; küçük bir alan
  yeter. Denenince harita dosyası ve ekran görüntüsü paylaşılır; 0.17 onunla
  yapılır.
- [ ] **0.3 İlk saha kaydı** (K). Test binasında beacon'lı bir yürüyüş,
  en az 8 "Buradayım" işaretiyle.
  *Bitti:* `FieldLogReplayTest` raporu eski ve yeni konum motorunu
  karşılaştırıyor, sonuç bu dosyanın ilerleme kaydına yazıldı.
- [ ] **0.4 Parçacık filtresi ayarları** (K). 0.3'ün kayıtlarıyla yön
  toleransı, parmak izi yayılımı ve adım ölçeği ayarlandı.
  *Bitti:* yeni motorun ortalama hatası eskisinden düşük. Değilse neden
  olduğu yazıldı ve plan buna göre değişti.
  Saha kaydında ayrıca denenecek (0.8'in simülasyonundan): tarayıcıdaki
  RSSI yumuşatması (`KalmanFilter1D`, Q = 0,5) yürürken sinyali birkaç metre
  geriden getiriyor; simülasyonda Q = 2 hatayı belirgin düşürdü. Haritalar da
  aynı yumuşatmayla çıkarıldığı için yalnızca saha kaydıyla değiştirilecek.
- [x] **0.5 Bilinen hata: adım modunda kayda devam edince yol baştan
  başlıyor** (K). Durdurup "Kayda devam et" denince adım sayımı (0, 0)'dan
  yeniden başlıyor.
  *Bitti:* devam eden yürüyüş kaldığı noktadan sürüyor, testle korunuyor.
- [ ] **0.6 Branch'leri birleştir** (K). `feat/graph-particle-filter` ve
  `feat/ar-survey` için `development`'a PR açıldı ve birleştirildi.
- [ ] **0.7 Harita kaydı uygulamadan çıkınca kaybolmasın** (O). **Kod hazır:**
  kayıt sırasında uygulama zorla kapatılıp yeniden açılarak denenecek. Kayıt bugün
  yalnızca bellekte; kullanıcı başka uygulamaya geçince Android uygulamayı
  kapatırsa saatlerce süren yürüyüş kaybolur. Kayıt sürerken ön plan hizmeti
  ve bildirim; kayıt diske taslak olarak yazılır, açılışta "bitmemiş kayıt"
  olarak geri gelir.
  *Bitti:* kayıt sırasında uygulama zorla kapatılıp açılınca yürüyüş, isimli
  yerler ve okunan sinyaller yerinde; testle korunuyor.

- [x] **0.8 Sanal saha testi** (O). Bir binayı ve içinde yürüyen kişiyi
  simüle eden test düzeneği: gürültülü adımlar, uzaklıkla zayıflayan ve
  dalgalanan beacon sinyalleri, yer işaretleri. Filtre ayarlarını gerçek
  kayıt gelmeden kabaca yapmaya ve konum doğruluğunu her değişiklikte
  otomatik denetlemeye yarar.
  *Bitti:* birkaç senaryoda hata eşikleri testle korunuyor.
  `positioning/.../simulation/`: 40 m'lik ana koridor, döngü yapan iki yan
  koridor, 9 beacon; telefonun kendi yumuşatması ve haritalamanın kendi
  örneklemesi (`RadioSampler`). `VirtualFieldTest` 8 senaryoyu (yalnız adım,
  uzun yürüyüş ve jiroskop kayması, kısa adım, beacon, kötü ve seyrek sinyal,
  yanlış başlangıç) 10'ar farklı rastgelelikle çalıştırıyor.
  `UMAY_SIM_REPORT=1` ile sayıları yazdırıyor.
- [ ] **0.9 Telefonda saha kaydı özeti** (K). **Kod hazır:** Dosyalar →
  Saha kayıtları, her kaydın altında (`FieldLogSummary`); telefonda
  denenecek. Dosyalar'da her kaydın
  "Buradayım" işaretlerinden ortalama ve en kötü hatası, en kötü yerin adıyla.
  *Bitti:* sahada kaydı göndermeden doğruluk görülebiliyor.
- [x] **0.10 Gerçek açık veriyle tekrar** (O). Microsoft'un Indoor Location
  Competition 2.0 verisi (gerçek binalarda, elde tutulan Android telefonla:
  ivmeölçer, jiroskop, iBeacon taramaları ve işaretli gerçek konumlar)
  uygulamanın kendi adım bulma ve konum koduyla oynatılır. Kendi binamız
  olmadan gerçek adım ve sinyal gürültüsüyle ölçüm sağlar. Örnek veri MIT
  lisanslı; depoya girmez, `UMAY_ILC_DATA` ile gösterilir (`IlcReplayTest`).
  Koridor ağı katın diğer yürüyüşlerinin işaretli noktalarından çıkarılır.
  *Bitti:* en az 3 binada eski ve yeni motorun hatası ilerleme kaydında.
  Açık örnek veride yalnız 2 bina (alışveriş merkezi) var: 2 binanın 3
  katında, 179 yürüyüşte ölçüldü. Karşılaştırılan "eski motor" yalnız adım
  saymadır ve ona gerçek başlangıç yönü de verilmiştir. Sonuçlar
  ilerleme kaydında (11 Ekim). Bunun için adım bulma Android'den ayrıldı
  (`StepDetection`).
- [x] **0.11 Yürüyüşü izleme sayfası** (K). Simülasyonun, saha kaydının ve
  0.10'un yürüyüşlerini harita üzerinde oynatan bir sayfa: gerçek yol,
  tahmin, parçacık bulutu, o an söylenen yönerge, hata grafiği. Kod
  değişikliğinin etkisini gözle görmeye yarar.
  *Bitti:* bir test komutu, açılınca yürüyüşü oynatan tek bir HTML dosyası
  üretiyor.
  `simulation` modülü, `WalkViewerPage`. Sanal ofis için `WalkReportsTest`,
  saha kayıtları için `UMAY_FIELD_LOGS` ile `FieldLogRecordingTest` (README
  "Saha testi"). Saha kaydı bugünkü kodla yeniden oynatılır ve yürüyüş
  sırasındaki tahminle yan yana gösterilir. Sayfa internete istek yapmaz.
- [ ] **0.15 Simülatörü sahaya göre ayarlamak** (O). İlk saha kayıtları
  (0.3) gelince aynı bina simülasyonda kurulur ve hata dağılımları
  karşılaştırılır: sinyal, adım ve kayma değerleri kayıtlardan alınır.
  Simülasyon sahayı tutturamıyorsa sayılarına güvenilmez. Yürüyen modeline
  konuşma süresi, mesafeyi yanlış tahmin, yana kayma eklenir; ayar seçilen
  senaryolar ile doğrulananlar ayrılır.
  **Kod tarafı yapıldı:**
  - Yürüyen artık cümleleri söylendikleri sürede duyuyor (saniyede 15
    harf, `SpokenQueue`). Mesafeyi %25 kadar yanlış tahmin ediyor,
    koridorun ortasından 0,5 m'ye kadar kayıyor. Telefon konuşurken bir
    duvara, kapıya ya da asansöre varınca durup dinliyor.
  - Ayar ile doğrulama ayrıldı. Uygulamanın hiçbir sabiti sanal klinikte
    (`clinicFloor`, `ValidationWalkTest`) seçilmiyor. Ofiste 36/36 varan
    yürüyüşler klinikte işaretlerle 32/36; yalnız beacon'la 21/36, pusula
    ipucuyla 30/36. Uzun koridorda tahmin geride kalıyor. Kapıdan
    başlayınca yön ilk köşeden önce öğrenilemiyor.
  - Hâlâ açık: yanlış başlangıç noktasında yer işaretleri gerçekçi
    yürüyende kurtarmıyor (8/24, işaretsiz de 8/24). Merdivenin 3 m
    önünden başlayan barometresiz bir yürüyüşte tırmanış "rotadan çıktın"
    sayılabiliyor (1/6).
  *Bitti:* bir binada simülasyonun ve sahanın ortalama konum hatası %30
  içinde (saha kaydı bekliyor).
- [x] **0.12 Sanal ofis** (B). Sanal açık ofis (duvarlar, masalar, odalar,
  beacon'lar, yer işaretleri) ve uygulamanın söylediklerine göre yürüyen,
  binayı bilmeyen sanal kör kullanıcı (`GuidedWalkTest`). Kamera, telefonun
  göreceği kareleri çiziyor ve işaretleri uygulamanın kendi okuyucusu okuyor
  (`MarkerRangeTest`). Engeller, algılayıcının yerine geçen bir modelle
  görülüyor, uzaklıkları uygulamanın kendi koduyla ölçülüyor
  (`ObstacleWalkTest`).
  *Bitti:* bir ofis sahnesinde engel ve yer işareti senaryoları
  otomatik koşuyor, sonuçlar ilerleme kaydında.
- [ ] **0.19 Klinikteki açığı kapatmak** (O). 0.15'in doğrulama binası,
  uygulamanın ofise ve kurum katına göre ayarlandığını gösterdi. Klinikte
  yalnız beacon'la 21/36 yürüyüş varıyor, ofiste 34/36. İki neden görüldü:
  uzun koridorda tahmin 2–2,5 m geride kalıyor (adım boyu farkı beacon'la
  düzelmiyor), kapıdan başlayınca yön ilk köşeden önce öğrenilemiyor.
  Kural: düzeltme ayar binalarında (ofis, kurum katı) seçilir, klinik
  yalnız sonucu ölçer. Klinikteki sayılar sabit seçmek için kullanılırsa
  doğrulama değeri kalmaz; o zaman yeni bir doğrulama binası gerekir.
  İlk deneme: geride kalma kurum katının 40 m koridorunda da var (sonda
  1,5–2,8 m, adım boyu farkının %5–6'sı kadar). Parçacıkların adım
  ölçeğine adım adım küçük bir rastgele yürüyüş eklendi, etkisi olmadı
  (0,1 m), geri alındı. Ölçek çeşitliliği eksik değil; 6–10 m arayla
  duyulan beacon'lar koridor boyunca 1–2 m'yi ayırt etmiyor. Olası yollar:
  adım boyu kalibrasyonunu yürüyüşe katmak, beacon'un yanından geçerken
  sinyalin tepe yapmasını konum düzeltmesi olarak kullanmak. İkisi de
  simülatörün radyo modeline göre değil, saha kayıtlarına (0.3) göre
  ayarlanmalı.
  *Bitti:* klinikte yalnız beacon'la en az 30/36, ofiste kötüleşme yok.
- [ ] **0.14 Foto-gerçekçi ofis kopyası** (B). Gerçek bir ofisin telefon
  videosundan Gaussian splatting ile 3B kopya (nerfstudio ya da Mac'te
  OpenSplat; tek seferlik GPU işi). Kareleri 0.12'nin sanal kamerası yerine
  bu kopyadan alınır, engel algılayıcının kendisi (EfficientDet) bu karelerde
  çalıştırılır. 0.12 engel algılamayı varsayıyor; bu onu ölçer. Bir ofisin
  telefonla, yavaşça dolaşılarak çekilmiş 3–5 dakikalık videosunu gerektirir.
  *Bitti:* gerçek bir ofisin kopyasında engel algılamanın isabeti ve uzaklık
  hatası ölçüldü.
- [ ] **0.13 Emülatörde uçtan uca deneme** (O). Açık karar 3'e bağlı.
  Sensörleri ve beacon'ları kayıttan ya da simülasyondan besleyen bir test
  sürümü; emülatörün sanal kamerasına 0.12'nin kareleri verilir. Uygulamanın
  kendisi (ekranlar, TalkBack, konuşma) cihazsız baştan sona denenir.
  *Bitti:* kayıtlı bir yürüyüş emülatörde baştan sona oynuyor, söylenen
  yönergeler kayıt altında.

- [x] **0.16 Çok katlı sanal bina** (O). Merdiven ve asansörlü iki katlı
  sanal ofis (`VirtualBuildings.twoFloorOffice`). Kat değişimi (basınç
  sensörü olan ve olmayan telefon, adımla kendiliğinden onay, elle onay),
  merdivensiz rota ve kat değiştiren rehberli yürüyüş senaryoları
  (`MultiFloorWalkTest`). Kat onayı kararı uygulamanın kendi kodu
  (`FloorChangeConfirmation`, `NavigationSession`'dan ayrıldı).
  *Bitti:* kat değiştiren yürüyüşler hata eşikleriyle testte.
- [ ] **0.17 Gerçek haritayla sanal yürüyüş** (K). 0.2'de çıkarılan
  gerçek bir haritayı (bir koridor ya da oda) simülasyona yükleyip sanal kör
  kullanıcıyı onun üstünde yürütmek. Gerçek haritanın kusurlarının
  (eksik yol, yanlış birleşen yer, duvar lekesi) yönlendirmeyi nasıl
  etkilediği telefonla gidip gelmeden görülür.
  *Bitti:* bir gerçek harita dosyası `WalkReportsTest` ile oynatılıyor.
- [x] **0.18 Kamerayla haritalamayı simüle etmek** (O). Sanal haritacı
  kamerayla da haritalıyor (`VirtualCameraSurveyor`). ARCore'un yön ve ölçek
  kayması, izi kaybetmesi ve izi yanlış yerde geri bulması modellendi.
  Derinlik görüntüleri binanın duvarlarından ve eşyalarından ışın atılarak
  üretiliyor. Harita uygulamanın kendi koduyla çıkarılıyor
  (`ArPoseAligner`, `DepthProjector`, `OccupancyGrid`, `WallMapBudget`,
  `MapDoors`, `OpenSpaceLinks`). `CameraSurveyTest`: iz yerinde geri
  geliyorsa yerler ortalama 0,1–0,15 m, duvar hücrelerinin %80–100'ü
  gerçek; rehberli yürüyüşlerin 11–12/12'si varıyor. Zayıf izlemede (sık
  kayıp, yarısı yanlış yerde geri gelen) yerler ortalama 0,3–0,7 m,
  varış 33/36.
  *Bilinen sınır:* iz yanlış yerde geri gelince sonrası birkaç desimetre
  kayık çiziliyor; yerler doğru kalsa da duvar haritasının isabeti %35–75'e
  düşüyor.

### Faz 1: Gizlilik ve kurum içi çalışma

Kurumla konuşmadan önce kapanması gereken açıklar. Hepsi küçük ve net.

- [ ] **1.1 Konuşma tanıma yalnızca cihazda** (K). **Kod hazır:** uçak
  modunda ve Android 12 altı bir telefonda denenecek. `SpeechToText` bugün
  çevrimdışı tanımayı zorlamıyor, ses Google'a gidebilir. Android 12+ için
  cihaz içi tanıyıcı, eski sürümler için `EXTRA_PREFER_OFFLINE` kullanılacak.
  Cihaz içi tanıma yoksa özellik kapalı olacak ve nedeni söylenecek.
  *Bitti:* uçak modunda "Bir şey sor" çalışıyor ya da çalışamayacağını
  söylüyor; ağ açıkken de ses cihazdan çıkmıyor.
- [ ] **1.2 ARCore açıklaması** (K). **Kod hazır:** bağlantıların TalkBack
  ile okunup açıldığı denenecek. Google'ın kuralı gereği, ARCore'un
  Google tarafından sağlandığı ve Google Gizlilik Politikası'na tabi olduğu
  uygulama içinde görünür şekilde söyleniyor (Ayarlar → Hakkında ve
  kamerayla haritalama ekranı).
  *Bitti:* metin iki yerde de var ve TalkBack ile okunuyor.
- [x] **1.3 Kurum sürümü** (O). Firebase'siz ayrı bir derleme türü
  (`institution`, "Umay Kurum"): paylaşımlı havuz yok, Firebase kütüphaneleri
  pakette yok, yedekleme kapalı.
  *Bitti:* APK'da Firebase sınıfı yok. Harita aktarımı dosyayla ya da 1.5
  ile yapılıyor.
- [x] **1.4 Ağ kısıtı** (K). Sunucusuz hâli yapıldı: internet izni yok,
  hiçbir sertifikaya güvenilmiyor, `verifyInstitution*Privacy` derlemede
  denetliyor. Sunucu adresine izin veren hâli 1.5 ile gelir. Kurum sürümünde ağ güvenlik yapılandırması
  yalnızca derleme sırasında verilen kurum sunucu adresine izin veriyor.
  Sunucu yoksa uygulamanın internet izni de yok.
  *Bitti:* başka bir adrese istek atılamadığı testle gösterildi.
- [ ] **1.5 Kurum içi harita sunucusu** (O). **Kod hazır:** `server`
  modülü, `HttpBuildingMapRepository`, sunucu adresiyle derlenen kurum
  sürümü yalnızca o adrese bağlanır ([SUNUCU.md](SUNUCU.md)). Sunucu ve
  istemci gerçek HTTP üzerinden test edildi; iki telefonla kurum ağında
  denenecek. Kurumun ağında çalışan basit
  bir sunucu: haritaları listeler, indirir, yetkili cihazdan yükleme kabul
  eder. Uygulama tarafında `RemoteBuildingMapRepository`'nin ikinci bir
  gerçeklemesi. Protokol bu klasörde belgelenir.
  *Bitti:* iki telefon aynı kurum ağında birinin çıkardığı haritayı
  diğerine aktarıyor. İnternet kapalıyken de çalışıyor.
- [x] **1.6 Gizlilik belgesi** (K). `docs/GIZLILIK.md` yazıldı. Hangi veri nerede durur, ne zaman silinir,
  hangi Google bileşeni ne yapar. Kurumlar için `docs/GIZLILIK.md`, ayrıca
  Play Store veri güvenliği formu için özet.
  *Bitti:* belge yazıldı, uygulamadaki davranışla birebir.
- [x] **1.7 Web sitesindeki gizlilik politikası** (K). `feat/website`
  dalında güncellendi; uygulamanın bu sürümüyle birlikte yayınlanacak. `feat/website`
  dalındaki `gizlilik.html` ve `en/privacy.html`, `docs/GIZLILIK.md` ile
  uyumlu hâle getirilir: cihaz içi konuşma tanıma, ARCore, ML Kit ölçümleri,
  kurum sürümü.
  *Bitti:* iki sayfa da belgeyle çelişmiyor.

### Faz 2: Harita, kapılar ve düzenleme

- [ ] **2.1 Kapılar haritanın parçası** (O). **Kod hazır:** kapısı kapalıyken
  kamerayla haritalanan bir katta açıklığın göründüğü denenecek. Kapının hangi
  iki alanı bağladığı, alanlar modellenmediği için kapının iki yanındaki
  bağlantılardan anlaşılıyor. Haritayı çıkaran kişi bir
  kapıyı işaretlediğinde, kapı duvar haritasında bir açıklık olarak
  kaydediliyor (konum, genişlik, hangi iki alanı bağladığı). Navigasyon
  haritasında kapı olarak çiziliyor.
  *Bitti:* kapalı bir kapı duvar haritasında duvar gibi görünse bile
  haritada kapı olarak duruyor, rota ondan geçebiliyor.
- [x] **2.2 Harita format sürümü** (K). Sürüm 2; daha yeni sürümlü
  dosyalar "uygulamayı güncelle" denerek reddediliyor. `.umay.json`'a bir sürüm numarası
  ekleniyor, eski sürümler okunurken yükseltiliyor.
  *Bitti:* eski haritalar açılıyor, yeni alanlar testle korunuyor.
- [ ] **2.3 Harita düzenleme** (O). **Kod hazır:** Dosyalar → Düzenle;
  TalkBack ile baştan sona denenecek. Kaydedildikten sonra yer adı ve türü
  değiştirme, yer silme, yanlış bağlantıyı kaldırma, duvar haritasında
  hatalı lekeyi silme. Erişilebilir olmalı (liste tabanlı düzenleme).
  *Bitti:* bir haritadaki hatalar yeniden haritalamadan düzeltiliyor.
- [ ] **2.5 Haritanın bir kısmını yeniden çıkarmak** (O). **Kod hazır:**
  Beacon Tarayıcı'da yürüyüş bitince "Var olan bir haritaya bölüm olarak
  ekle". Bölüm haritada olan en az iki yerden (aynı adla) geçmeli; o yerler
  bölümü haritaya oturtur (döndürme, kaydırma, adım boyu hatası için ölçek).
  Yeni yerler eklenir, yeniden yürünen yolun eskisi kaldırılır (istenirse
  kalır), bölümün gördüğü duvar hücreleri ve sinyal örnekleri eskisinin
  yerine geçer (`MapSections`). Telefonda denenecek. Masalar ve
  sandalyeler yer değiştirir (ofis, bekleme salonu); bütün binayı baştan haritalamak
  yerine bir bölümü yeniden yürüyüp mevcut haritaya eklemek: bilinen bir
  yerde başlayıp bilinen bir yerde bitirmek, aradaki yol ve duvar haritası
  eskisinin yerine geçer.
  *Bitti:* bir odanın düzeni değişince yalnız o oda yeniden yürünüp harita
  güncelleniyor.
- [ ] **2.4 Sık beacon parmak izi** (O). **Kod hazır:** her iki haritalama
  modunda 3 metrede bir örnek; filtre her taramayı kullanıyor (simülasyonda
  %14 kısa adımla 25 m sonunda hata 3,7 m'den 1,4 m'ye indi). Saha kaydında
  ölçülecek. Parmak izi yalnızca isim verilen
  noktalarda değil, kamerayla çıkarılan yol boyunca birkaç metrede bir
  kaydediliyor.
  *Bitti:* parçacık filtresi beacon'ı yalnızca güvenilir eşleşmede değil,
  her taramada yumuşak bir kanıt olarak kullanıyor. Saha kaydında hata
  düşüyor.

### Faz 3: Konum, "neredeyim"

- [ ] **3.1 TalkBack ile başlangıç** (K). **Kod hazır:** kör bir test
  kullanıcısıyla denenecek. "Bulunduğum yer" seçicisi en yakın
  yerleri öne alıyor (beacon varsa). Seçim sesli onaylanıyor.
  *Bitti:* kör bir test kullanıcısı başlangıcı yardımsız seçiyor.
- [ ] **3.2 Beacon ile kendiliğinden başlangıç** (K). **Kod hazır:** öneri,
  aynı yer üç taramada üst üste güvenilir eşleşme olunca soruluyor; test
  binasında 10 denemeyle ölçülecek. Kurumda uygulama
  açılınca en olası yeri öneriyor: "Giriş holündesin gibi görünüyor, doğru
  mu?"
  *Bitti:* test binasında 10 denemenin en az 8'inde doğru yeri öneriyor.
- [ ] **3.3 Kamerayla konum bulma** (B). Karar 1 gereği ARCore'suz: isimli
  yerlere yapıştırılan basılı QR yer işaretleri (düzenleyiciden yazdırılır,
  15 cm). Yönlendirmede kamera işareti görünce uzaklık ve yön parçacık
  filtresine en güçlü kanıt olarak girer, kat ve yön de düzelir; yola
  çıkmadan "Kamerayla bul" başlangıcı işaretten bulur. Hepsi cihazda, ZXing
  (saf Java) ile. **Kod hazır:** basılı işaretlerle denenecek. Görsel yer
  tanıma (işaretsiz) ileride, ihtiyaç saha testinde görülürse.
  *Bitti:* kullanıcı oturduğu yerde telefonu birkaç saniye çevirince konumu
  1 m içinde bulunuyor. Benzer iki koridor karıştığında beacon ayırıyor.
  Sahada ölçüldü.
- [ ] **3.4 Konum kaynaklarının birleşmesi** (O). **Kod hazır:** adım,
  beacon (tarama ve parmak izi), harita ve yer işaretleri tek parçacık
  filtresinde birleşiyor; kamera kapanınca (cep, ekran, ısı) konum
  zıplamadan adım ve beacon'la sürüyor. Sahada ölçülecek. Kamera varken kamera,
  telefon cebe girince adım ve beacon, kamera geri gelince yeniden kamera.
  Geçişlerde konum zıplamıyor.
  *Bitti:* bir yürüyüş boyunca telefon iki kez cebe girip çıkıyor, saha
  kaydında hata sınırın içinde kalıyor.

- [ ] **3.5 Pusulayla başlangıç yönü** (O). **Kod hazır:** haritalama her
  adımda haritanın yönünü pusulayla karşılaştırıp katın farkını haritaya
  yazıyor (`CompassSums`, `FloorMap.compass`); yönlendirme başlarken pusulayı
  2 saniye kadar okuyup konum motoruna "şu yöne bakıyor, ±30°" diyor
  (`CompassHint`). Pusula kararsızsa ya da harita eski ise ipucu yok, her şey
  eskisi gibi. Gerçek yürüyüşlerde (0.10, uygulamanın kendi koduyla) açık
  alanlı katta ortalama hata 9,5 m'den 4,8 m'ye, koridorlu katlarda %15–25
  düştü; üç farklı rastgelelikte de aynı. Sahada denenecek.
  *Bitti:* harita farkı saklıyor, yönlendirme ipucunu kullanıyor; sahada
  denendi.
- [ ] **3.6 İşaretleri daha uzaktan okumak** (O). **Kod hazır:** kamera
  1280 × 960 açılıyor, işaretler 960 genişlikte okunuyor (~2 m); pil
  tasarrufunda 640 (~1 m). Pil ve ısı etkisi telefonda ölçülecek (4.4). Yönlendirmede işaretler
  480 × 640 karelerden okunuyor: 15 cm'lik kod ancak ~1 m'den okunuyor,
  960 × 1280'de 2 m'den (0.12'de ölçüldü). Kamerayı daha yüksek çözünürlükte
  açıp işaretleri tam karede, engel algılamayı küçültülmüş karede çalıştırmak.
  Pil ve ısıya etkisi telefonda ölçülmeli (4.4).
  *Bitti:* işaret 2 m'den okunuyor, pil tüketimi kabul edilebilir.

### Faz 4: Kamerayla ilerleme

- [ ] **4.1 Kamerayla engel uyarısı** (O). Karar 1 gereği derinlik
  sensörsüz: aynı model artık insanla birlikte sandalye, kanepe, masa, bank,
  yatak, saksı, bisiklet, valiz, köpek gibi eşyaları da tanıyor ve adıyla
  söylüyor ("yaklaşık 2 metre önünde bir sandalye var"). Duvar ve kapalı
  kapı haritadan bilinir; rota koridorda kalır. **Kod hazır:** test
  parkurunda denenecek.
  *Bitti:* test parkurunda bütün engeller uyarıldı, yanlış uyarı az.
- [ ] **4.2 Serbest alanda rota** (O). Karar 1 gereği konum koridor ağında
  tutulduğu için şöyle yapıldı: kamerayla çıkarılan katta, aralarındaki düz
  yol ve iki yanındaki 40 cm görülmüş boş zemin olan yerler, yürünen yol çok
  daha uzunsa doğrudan bağlanıyor; açık lobide dolanmadan geçiliyor.
  **Kod hazır:** test binasının açık alanında denenecek. Duvar haritası üzerinde, duvardan
  güvenli mesafe bırakan yol bulma. Kullanıcı koridor çizgisinin dışından
  (bekleme salonundaki bir koltuk, açık bir lobi) başlayabiliyor. Graf
  rotasıyla birleşiyor.
  *Bitti:* test binasının açık bir alanında, koridor dışındaki bir noktadan
  rota çıkıyor, duvarlara sürtmüyor.
- [ ] **4.3 Yapıya göre yönerge** (O). **Kod hazır:** bacak boyunca bir
  yanda duvar varsa "Duvar solunda; onu takip edebilirsin" ekleniyor; kapı
  uyarısı ve engeller adıyla söyleniyor. Kör kullanıcılarla denenecek. "Kapıdan geç, sağa dön", "duvar
  solunda kalsın" gibi kapıya ve duvara dayalı cümleler.
  *Bitti:* yönergeler kör test kullanıcılarıyla denendi, anlaşılırlıkları
  yazıldı.
- [ ] **4.4 Pil ve ısı** (K). **Kod hazır:** "ciddi" ısıda kamera durur,
  "orta" ısıda ya da %20 altı pilde seyrekleşir, ikisi de söylenir.
  Tüketim 30 dakikalık yürüyüşte ölçülecek. Kamera açıkken pil tüketimi ölçülüyor ve
  sınırlanıyor (derinlik sıklığı, düşük çözünürlük). Isınınca uygulama
  adım ve beacon moduna iniyor ve bunu söylüyor.
  *Bitti:* 30 dakikalık yönlendirmede tüketim ölçüldü ve yazıldı.
- [ ] **4.5 Telefonu tutma** (K). **Kod hazır:** kamera yere ya da tavana
  bakınca söyleniyor; kurulum kılavuzu göğüs askısı öneriyor. Göğüs askısı veya boyun askısı önerisi.
  Uygulama telefonun yanlış tutulduğunu (yere ya da tavana baktığını) sesle
  söylüyor.

- [ ] **4.6 Rotadan çıkınca uyarı ve yeniden rota** (O). **Kod hazır:**
  `RouteMonitor`; sahada yanlış alarm olup olmadığı denenecek. Konum birkaç adım
  boyunca rotadan uzaklaşırsa ya da kullanıcı ters yöne yürürse "Rotadan
  çıktın" denir ve bulunduğu yerden yeni rota çıkarılır.
  *Bitti:* simülasyonda yanlış koridora sapan yürüyüşte uyarı ve yeni rota
  geliyor; doğru yürüyüşte yanlış alarm yok.
- [ ] **4.10 Yanından geçilebilecek engel için rota değişmesin** (K).
  **Kod hazır:** engelin genişliği kameradaki kutudan ölçülüyor (kutu kareden
  taşıyorsa bilinmiyor sayılıyor), yanındaki boşluk duvar haritasından
  (`Passage`); en az 80 cm varsa rota kalıyor ve "Engelin solundan
  geçebilirsin." deniyor. Sanal ofiste sandalyeli yürüyüşte yeni rota 11'den
  0'a indi. Duvar haritası olmayan katta eskisi gibi yeni rota. Gerçek
  engellerle telefonda denenecek.
  Rotadaki her durağan engel (geniş bir koridordaki sandalye da) yeni rota
  çizdiriyor; sanal ofiste bu yürüyüşü %55 uzattı. Duvar haritası varsa
  engelin yanındaki boşluk hesaplanıp geçilebiliyorsa yalnızca uyarılmalı.
  *Bitti:* sanal ofiste sandalyeli yürüyüşte yeni rota yok, uyarı var.
- [ ] **4.7 "Neredeyim?"** (K). **Kod hazır:** "Bir şey sor" ile. Sesli
  soruya en yakın yer, iki yer
  arasındaysa ikisi, rotadaysa sıradaki hedef ve uzaklığıyla yanıt.
- [ ] **4.8 Merdivensiz rota** (K). **Kod hazır:** Ayarlar → Rota. Ayarlardan
  "Merdiven kullanma": rota merdiven geçişleri yerine asansör ve rampadan
  çıkar; yoksa yola çıkmadan söylenir. Engel ve rotadan çıkma yüzünden
  yeniden çizilen rotalar da aynı kurala uyar.
- [ ] **4.9 Saat yönüyle anlatım** (K). **Kod hazır:** Ayarlar → Rota →
  "Yönleri saatle söyle". "Saat 2 yönünde" anlatımı; dönüşün açısına göre
  saat (45° sağ = 2, 90° sağ = 3), duvar tarafı 9 ya da 3. Varsayılan
  "sağa dön" kalır. Kör kullanıcılarla testte (5.3) hangisinin anlaşıldığı
  sorulacak.

### Faz 5: Kurumda yayın

- [ ] **5.1 Beacon yönetimi** (O). **Kod hazır:** Dosyalar → Düzenle →
  Beacon durumu; duyulmayanlar, Eddystone-TLM pil voltajı, haritada
  olmayanlar ve kapsama boşlukları. Gerçek beacon'larla denenecek. Kurumdaki beacon'ların listesi, son
  görülme zamanı, zayıf pil uyarısı, yerleştirme önerisi ("bu koridorda
  beacon yok").
- [x] **5.2 Kurulum kılavuzu** (K). `docs/KURULUM.md`. Bir kurumun Umay'ı kurması: beacon
  yerleştirme, harita çıkarma, sunucu, test.
- [ ] **5.3 Kör kullanıcılarla test** (O). Protokol hazır:
  [KULLANICI_TESTI.md](KULLANICI_TESTI.md). Test yapılacak. En az 5 kişiyle, gerçek bir
  binada, gözlemli. Sonuçlar ve düzeltmeler yazılı.
- [ ] **5.4 Erişilebilirlik denetimi** (K). Kod üzerinden tarama yapıldı ve
  bulunanlar düzeltildi (rolsüz tıklanabilir satırlar, TalkBack'e görünmeyen
  harita önizlemesi). TalkBack, büyük yazı ve yüksek kontrastla telefonda
  denetim bekliyor. Bütün ekranlar TalkBack,
  büyük yazı ve yüksek kontrastla denetlendi.
- [ ] **5.5 Mağaza hazırlığı** (K). 16 KB sayfa boyutu çözüldü: insan
  algılama LiteRT'ye taşındı, APK'daki bütün native kütüphaneler 16 KB
  hizalı. Veri güvenliği özeti `docs/GIZLILIK.md`'de. Veri güvenliği formu, ARCore açıklaması,
  güvenlik uyarıları, 16 KB sayfa boyutu (`libtask_vision_jni.so`, Şubat
  2027 son tarih).

---

## Kararlar

- **10 Ekim 2026: Yönlendirmede ARCore yok.** ARCore yalnızca haritayı
  çıkaran kişinin telefonunda (kamerayla haritalama). Kör kullanıcının konumu
  beacon, adım, harita ve basılı QR yer işaretleriyle; engel uyarısı aynı
  kameranın nesne algılamasıyla bulunur. Her telefonda çalışır, kullanıcının
  telefonunda Google AR bileşeni olmaz.
- **10 Ekim 2026: Kurum sunucusu yazılır.** Dosyayla aktarım da kalır; kurum
  hangisini kullanacağını kurulumda seçer.
- **11 Ekim 2026: Beacon'sız mekânlar kapsam dışı.** Umay beacon'lı binalar
  içindir. Kafe, dükkân gibi beacon koyulmayacak yerler hedeflenmez; harita
  çıkarmak oralarda da çalışsa bile konum doğruluğu için iş yapılmaz.
- **10 Ekim 2026: Ev ikinci senaryo.** Faz 0–5 kurum binası (beacon'lı) içindir.
  Ev için gereken beacon'sız konum, kurum senaryosu bittikten sonra ayrıca
  planlanır.

## Açık kararlar

Bunlar bir sonraki adımı doğrudan etkiler. Karar verildikçe yukarıdaki
**Kararlar** bölümüne taşınır.

1. **Test binası ve kurum.** Faz 0.3 ve Faz 3 için hangi bina?
2. **Hedef telefonlar.** Yönlendirme ARCore istemediği için Android 7+ her
   telefon; kamerayla haritalama için ARCore destekli bir telefon gerekir.
   Kurum hangi telefonlarla test edilecek?
3. **Emülatör.** Şimdiye kadar uygulama emülatörde ya da telefonda
   çalıştırılmadı (yalnızca derleme, birim testi, lint). 0.13 bu kuralın
   test sürümü için gevşetilmesini gerektirir.

---

## Riskler

- **Kamerayla konum bulma beklenen doğruluğa ulaşmayabilir.** Birbirinin
  aynısı koridorlar, cam duvarlar ve kalabalık zorlar. Önlem: beacon her zaman
  yedek. 3.3 önce küçük bir alanda kanıtlanır, sonra büyütülür.
- **Telefonun göğüste tutulması gerekir.** Elinde baston olan biri için
  askı şart. Önlem: cepteki mod (adım ve beacon) her zaman çalışır kalır.
- **Pil.** Kamera ve derinlik sürekli açıkken pil hızlı biter. Önlem: 4.4.
- **Haritada olmayan yere gitmek.** Gerçek yürüyüş kayıtlarında (0.10)
  harita yürüyüşün %63–71'ini kapsayınca ortalama hata 2–3 m'den 11–13 m'ye
  çıktı: konum motoru yürüyeni bildiği koridorlarda tutuyor. Denenen iki
  çözüm işe yaramadı ve alınmadı: bulutun bir kısmını koridordan serbest
  bırakmak hatayı tam haritada 2,8 m'den 6–10 m'ye çıkardı; adımların
  haritaya uymamasından "kayboldum" demek, kaybolunan anların %70'ini
  yakalarken iyi adımların %20–26'sında yanlış alarm verdi. Önlem: tam
  haritalama (KURULUM.md 3. adım), yer işaretleri.
- **Yanlış başlangıç ve seyrek beacon.** Simülasyonda (0.8) kullanıcı yanlış
  yeri seçer ve beacon'lar da seyrek duyulursa konum toparlanmıyor. Önlem:
  başlangıç önerisi (3.2), "Kamerayla bul" ve yer işaretleri (3.3); kurulumda
  beacon aralığı 100–300 ms (KURULUM.md).
- **Sahada doğrulama eksikliği.** Bugüne kadar yazılan konum kodunun hiçbiri
  gerçek binada ölçülmedi. Önlem: kamerayla konum bulma (3.3, 3.4) ve
  kamerayla ilerleme (Faz 4) 0.2 ve 0.3 bitmeden başlamaz. 3.1 ve 3.2
  mevcut parmak izi eşleştirmesini kullanan arayüz işleridir; yazılabilir,
  bitti ölçütleri sahada doğrulanır.

---

## İlerleme kaydı

En yeni üstte.

- **11 Ekim 2026:** Sanal yürüyen gerçekçileşti (0.15) ve iki gerçek hata
  buldu, düzeltildi:
  - Yönergeler sırayla okunuyordu (`QUEUE_ADD`). Yeni yönerge, geçilen
    koridor hakkındaki eski cümlenin bitmesini bekliyordu. "Sola dön."
    kavşaktan sonra, "Hedefe ulaştın." kapı geçildikten sonra duyuluyordu.
    Konuşmanın süresiyle 36 yürüyüşten 20'si varıyordu, yavaş konuşmada
    8'i. Artık yeni yönerge, söylenmeyi bekleyen eski yönergeyi kesiyor
    (`SpeechQueuePolicy`); engel uyarısını ya da işaret okumayı kesmiyor.
    Varış 35–36/36.
  - Merdivende yeni rota hesaplanınca barometre kat değişimini
    kaçırıyordu: tırmanışı, yarıda yeniden kurulan başlangıçtan
    ölçüyordu. Kullanıcı üst kattayken uygulama "merdivenle yukarı çık"
    demeyi sürdürüyordu. Bir dakika içinde aynı kat için yeniden
    kurulduğunda ilk başlangıç korunuyor.
  - Yeni, ayarda kullanılmayan bir bina (sanal klinik) uygulamanın
    ofise göre ayarlandığını gösterdi (yukarıda 0.15).

- **11 Ekim 2026:** Kamerayla haritalama simüle edildi (0.18), iki gerçek
  hata buldu, düzeltildi:
  - ARCore izi kaybedip başka bir yönle geri bulursa (yanlış yeniden
    konumlanma) sonraki her şey o açıyla çiziliyordu; tek bir 9°'lik dönüş
    yerleri 5 m'ye kadar kaydırıyordu. Artık
    jiroskop iz kaybındaki dönüşü izliyor. Geri gelen yön ondan 5°'den fazla
    sapıyorsa harita geri döndürülüyor ve konum, adımların jiroskop
    yönünde vardığı yere bağlanıyor.
  - İz kayıpken adı verilen yer, izin kaybolduğu noktaya kaydediliyordu
    (sanal ofiste 3 m'yi aşan hata). Kayıp sürdükçe canlı konumu artık adımlar
    taşıyor.

- **11 Ekim 2026:** Çok katlı sanal bina (0.16) kat değişiminde dört gerçek
  hata buldu, düzeltildi; kat değiştiren 54 sanal yürüyüşün 53'ü varıyor:
  - "Asansörle yukarı çık" ancak tahmin asansöre 2 m yaklaşınca
    söyleniyordu; çıkmazdaki bir asansörde bekleyen biri adım atmadığı için
    hiç duymuyordu. Merdivene ya da asansöre giden yönerge artık sonunu da
    söylüyor ("…, sonra/oradan asansörle yukarı çık, Kat 1.").
  - Basınç sensörü kat değişimini yönlendirme beklemeden fark ederse
    yok sayılıyordu; onaylanınca da rota kullanıcıyı eski kata geri
    yolluyordu.
  - Çıkmazdaki merdivende 20 adım çıkış, konumu başka bir koridora
    kaçırıyordu.
  - Basınç sensörü onayı birkaç adım geç geliyor; konum artık
    sahanlıktan çıkan yollar boyunca yayılarak yeniden başlıyor.

- **11 Ekim 2026:** 0.9 (saha kaydı özeti telefonda), 4.10 (yanından
  geçilen engel), 3.5 (pusula) kodlandı. Yolda iki gerçek hata bulundu:
  - Telefonun yönü Android'in `getOrientation` açısından okunuyordu. Bu açı
    telefon dik tutulunca tanımsız; tepesi biraz öne eğilince (kamera yere
    bakınca) 180° dönüyor. Göğüs hizasında tutulan telefonda her sallanma
    sahte bir geri dönüş oluyordu. Dikey eksen etrafındaki dönme (`headingDeg`)
    ile düzeltildi.
  - Adım sayarak haritalamada "Kayda devam et"ten sonraki ilk adım,
    sensörün başlangıç açısını dönüş sanıyordu; devam eden harita rastgele bir
    açıyla dönüyordu.

- **11 Ekim 2026:** Plana eklendi: 0.16 çok katlı sanal bina, 0.17 gerçek
  haritayla sanal yürüyüş, 0.18 kamerayla haritalamanın simülasyonu, 2.5
  haritanın bir kısmını yeniden çıkarmak, açık karar 4 (beacon'sız küçük
  mekânlar). Kod tarafında yapılabileceklerin çoğu bitti; asıl darboğaz
  telefonda ve sahada doğrulama (0.1, 0.2, 0.3).

- **11 Ekim 2026:** Simülasyonun en iyimser varsayımı kaldırıldı: harita artık
  binanın kusursuz planı değil, adım sayarak haritalayan sanal bir kişinin
  uygulamanın kendi koduyla çıkardığı harita olabiliyor (`VirtualSurveyor`).
  Bu, gerçek bir eksik buldu. Adım boyu %11 yanlış, jiroskobu kayan bir
  haritacıda geri dönülen kavşaklar 5 m'den fazla kayıyor ve birleşmiyordu.
  Ofiste 4–6 kavşak birleşmedi, rehberli yürüyüşlerin yalnız 6–19/24'ü
  vardı. Adım sayarak çıkarılan haritaya döngü kapatma eklendi
  (`SurveyLoopClosure`): aynı ada geri dönmek aradaki kaymayı geri alıyor,
  15 m içindeki aynı adlar birleşiyor. Birleşmeyen kavşak 0, varış 23–24/24,
  yerlerin ortalama hatası 1,4 m'den 0,7 m'ye indi. Tek büyük döngüdeki
  dönme kayması bununla düzelmiyor (yalnız birleşme kazancı); KURULUM 3.
  adım buna göre güncellendi.

- **11 Ekim 2026:** Sanal ofis tamamlandı (0.12). Bulunup düzeltilenler:
  - Yönlendirmede işaretin ve engelin uzaklığını hesaplayan kamera
    varsayımları birbirini tutmuyordu. Dik tutulan telefonun karesinde
    işaretler gerçekte olduğundan %27 yakın, engeller ~%20 uzak
    sanılıyordu (uyarı geç geliyordu). Tek bir varsayımda (`CameraOptics`)
    birleşti.
  - Yeni rota (engel ya da rotadan çıkma yüzünden) arkada başlıyorsa önce
    "Geri dön.", rotanın başına varınca "Sola/Sağa dön." deniyor.
    Kapalı koridorlu yürüyüşlerde varış 6'da 3'ten 6'ya çıktı.
  - Ayakta duran bir kişi için artık yeni rota çizilmiyor; yalnız uyarı
    var. Yürüyüş %84 uzuyordu.
  - Yer işareti, konumu 5 m şaşmış bir bulutu da baştan kuruyor. Yanlış
    yerden başlayan yürüyüşlerde varış 24'te 19'dan 23'e çıktı.

  Ölçülenler: 15 cm'lik işaret ancak ~1 m'den ve 45°'den dik açıyla
  okunuyor (yeni iş 3.6, KURULUM düzeltildi). Normal yürüyüşte işaret
  neredeyse hiç okunmuyor (24 yürüyüşte 2); konum karıştığında okunan birkaç
  işaret varışı 3'ten 14'e çıkarıyor. Engeller ulaşılmadan 2–8 m önce
  uyarılıyor, hiçbir sanal yürüyüşte uyarısız çarpma yok. Yeni işler: 0.14
  foto-gerçekçi kopya, 3.6, 4.10.

- **11 Ekim 2026:** Cihazsız test (0.10, 0.11, 0.12'nin bir kısmı):
  - Yürüyüş izleyici (0.11) hazır: sanal ofis, saha kayıtları ve gerçek
    veri aynı sayfada oynuyor.
  - Sanal ofiste, uygulamanın söylediklerine göre yürüyen sanal bir kör
    kullanıcı iki yönlendirme hatası buldu. Bir sonraki bacak köşeden 2 m
    önce söylendiğinde, o köşedeki dönüş cümleden düşüyordu. Son bacak da
    hedef köşe ardındaysa yön söylemiyordu. "Sola dön." gibi bir açılışla
    düzeltildi: varış 48 yürüyüşte 22'den 48'e çıktı.
  - Gerçek veri (0.10; 2 alışveriş merkezi, 3 kat, 179 yürüyüş). Ortalama
    hata; harita yürüyüşü tam kapsıyor:

    | Kat | Adım sayma* | Uygulama | + pusula | + wifi ve pusula |
    |---|---|---|---|---|
    | site1/F1 (açık alan) | 4,25 m | 7,87 m | 4,56 m | 4,88 m |
    | site2/F4 | 5,73 m | 2,77 m | 2,50 m | 1,95 m |
    | site2/F7 | 3,48 m | 1,88 m | 1,50 m | 1,50 m |

    \* Yalnız adım sayma, gerçek başlangıç yönü verilerek.

    Harita yürüyüşün %63–96'sını kapsadığında uygulamanın hatası 11–15 m
    (Riskler). Yeni iş: 3.5 Pusulayla başlangıç yönü.

- **11 Ekim 2026:** Saha kaydı gelmeden yapılabilenler bitti: rotadan çıkma
  ve ters yön uyarısı (4.6), "Neredeyim?" (4.7), merdivensiz rota (4.8),
  saat yönüyle anlatım (4.9), sanal saha testi (0.8). Simülasyon parçacık
  filtresinde üç hata buldu ve düzeltildi: birkaç beacon'a dayanan yanlış
  yer eşleşmeleri yürüyeni metrelerce uzağa çekiyordu (seyrek sinyalde
  ortalama 6 m; artık eşleşme yalnızca sinyal haritası da onaylarsa
  uygulanıyor, 0,7 m); yer işareti yanlış başlangıcı düzeltince yön eski
  kalıyordu (artık işaretin görüldüğü yönden kuruluyor; 16 m'den 1,6 m'ye);
  kavşağı bir adım geçen sıkı bir bulut dönüşte yok oluyordu (artık 1 m
  tolerans var). Cihazsız test için 0.10–0.13 eklendi; 0.13 açık karar 3'e
  bağlı.

- **11 Ekim 2026:** Faz 1–5'in kodla yapılabilen bütün işleri bitti: kurum
  sunucusu (1.5), kaynakların birleşmesi (3.4), açık alan bağlantıları (4.2),
  duvara dayalı yönerge (4.3), ısı ve pil (4.4), tutuş uyarısı (4.5),
  kullanıcı testi protokolü (5.3). Bundan sonrası telefonda ve sahada
  doğrulama: Faz 0'daki denemeler ve "Kod hazır" yazan her işin bitti ölçütü.

- **10 Ekim 2026:** Karar 1 (yönlendirmede ARCore yok) ve karar 2 (kurum
  sunucusu) alındı. İnsan algılama LiteRT'ye taşındı ve 16 KB sorunu kapandı;
  engel uyarısı eşyaları da kapsıyor (4.1); QR yer işaretleriyle konum bulma
  ve "Kamerayla bul" yazıldı (3.3).

- **10 Ekim 2026:** Beacon durumu ekranı (5.1) ve kurulum kılavuzu (5.2)
  yazıldı; web sitesindeki gizlilik politikası güncellendi (1.7). Kılavuz
  yazılırken harita oluşturmada iki hata bulunup düzeltildi: bir kavşağa
  dönüp aynı adı vermek ikinci, bağlantısız bir nokta yaratıyordu (artık
  aynı yer sayılıyor ve koridorlar birleşiyor); başka bir kata çıkıp başka
  merdivenle dönülünce iki merdiven arasına duvarlardan geçen düz bir
  bağlantı ekleniyordu.

- **10 Ekim 2026:** Harita ve konum işleri yazıldı: harita kaydı ön plan
  hizmeti ve diskteki taslakla korunuyor (0.7); harita Dosyalar → Düzenle ile
  düzeltilebiliyor (2.3); yol boyunca 3 metrede bir sinyal örneği alınıyor ve
  parçacık filtresi her taramayı kanıt olarak kullanıyor (2.4); başlangıç
  noktası beacon'larla öneriliyor ve yakın yerler listenin başına geliyor
  (3.1, 3.2); kapılar duvar haritasında açıklık olarak kaydediliyor (2.1).
  Hepsinin birim testleri geçiyor; telefonda ve sahada doğrulanmaları
  bekleniyor.

- **10 Ekim 2026:** Telefon gerektirmeyen gizlilik işleri yapıldı. "Bir şey
  sor" artık yalnızca cihaz içi tanıma kullanıyor (1.1). ARCore bildirimi
  eklendi (1.2). Kurum sürümü eklendi: Firebase'siz, internet izinsiz,
  yedeklemesiz; derlemede denetleniyor (1.3, 1.4). `docs/GIZLILIK.md`
  yazıldı (1.6). Harita dosyasına format sürümü eklendi (2.2). Adım modunda
  kayda devam etme hatası düzeltildi (0.5).

  Yolda bulunup düzeltilenler: metin okuma internet üzerinden okuyan bir
  sese düşebiliyordu, artık yalnızca cihazdaki sesler seçiliyor. Bir
  kütüphanenin eski manifesti uygulamaya telefon durumu ve harici depolama
  izinlerini ekliyordu, kaldırıldı (Play Store sürümünü de etkiliyordu).
  Yeni iş eklendi: kayıt kaybına karşı 0.7, web sitesi için 1.7.

- **10 Ekim 2026:** Ev ikinci senaryoya bırakıldı. ARCore'un yönlendirmede
  kullanılmaması önerildi (açık karar 1, onay bekliyor).

- **10 Ekim 2026:** Yol haritası yazıldı. Parçacık filtresi
  (`feat/graph-particle-filter`) ve kamerayla harita çıkarma
  (`feat/ar-survey`) yazıldı ve birim testleri geçiyor. İkisi de sahada ve
  telefonda doğrulanmayı bekliyor (0.2, 0.3). Konuşma tanımanın ses
  verisini cihaz dışına çıkarabildiği (1.1) ve ARCore açıklamasının eksik
  olduğu (1.2) tespit edildi.
