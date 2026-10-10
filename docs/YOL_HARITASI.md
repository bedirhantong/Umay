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
| Duvar haritası üzerinde serbest rota (koltuktan başlamak) | Yok | |
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
- [ ] **0.3 İlk saha kaydı** (K). Test binasında beacon'lı bir yürüyüş,
  en az 8 "Buradayım" işaretiyle.
  *Bitti:* `FieldLogReplayTest` raporu eski ve yeni konum motorunu
  karşılaştırıyor, sonuç bu dosyanın ilerleme kaydına yazıldı.
- [ ] **0.4 Parçacık filtresi ayarları** (K). 0.3'ün kayıtlarıyla yön
  toleransı, parmak izi yayılımı ve adım ölçeği ayarlandı.
  *Bitti:* yeni motorun ortalama hatası eskisinden düşük. Değilse neden
  olduğu yazıldı ve plan buna göre değişti.
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
- [ ] **1.5 Kurum içi harita sunucusu** (O). Kurumun ağında çalışan basit
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
- [ ] **3.4 Konum kaynaklarının birleşmesi** (O). Kamera varken kamera,
  telefon cebe girince adım ve beacon, kamera geri gelince yeniden kamera.
  Geçişlerde konum zıplamıyor.
  *Bitti:* bir yürüyüş boyunca telefon iki kez cebe girip çıkıyor, saha
  kaydında hata sınırın içinde kalıyor.

### Faz 4: Kamerayla ilerleme

- [ ] **4.1 Kamerayla engel uyarısı** (O). Karar 1 gereği derinlik
  sensörsüz: aynı model artık insanla birlikte sandalye, kanepe, masa, bank,
  yatak, saksı, bisiklet, valiz, köpek gibi eşyaları da tanıyor ve adıyla
  söylüyor ("yaklaşık 2 metre önünde bir sandalye var"). Duvar ve kapalı
  kapı haritadan bilinir; rota koridorda kalır. **Kod hazır:** test
  parkurunda denenecek.
  *Bitti:* test parkurunda bütün engeller uyarıldı, yanlış uyarı az.
- [ ] **4.2 Serbest alanda rota** (O). Duvar haritası üzerinde, duvardan
  güvenli mesafe bırakan yol bulma. Kullanıcı koridor çizgisinin dışından
  (bekleme salonundaki bir koltuk, açık bir lobi) başlayabiliyor. Graf
  rotasıyla birleşiyor.
  *Bitti:* test binasının açık bir alanında, koridor dışındaki bir noktadan
  rota çıkıyor, duvarlara sürtmüyor.
- [ ] **4.3 Yapıya göre yönerge** (O). "Kapıdan geç, sağa dön", "duvar
  solunda kalsın" gibi kapıya ve duvara dayalı cümleler.
  *Bitti:* yönergeler kör test kullanıcılarıyla denendi, anlaşılırlıkları
  yazıldı.
- [ ] **4.4 Pil ve ısı** (K). Kamera açıkken pil tüketimi ölçülüyor ve
  sınırlanıyor (derinlik sıklığı, düşük çözünürlük). Isınınca uygulama
  adım ve beacon moduna iniyor ve bunu söylüyor.
  *Bitti:* 30 dakikalık yönlendirmede tüketim ölçüldü ve yazıldı.
- [ ] **4.5 Telefonu tutma** (K). Göğüs askısı veya boyun askısı önerisi.
  Uygulama telefonun yanlış tutulduğunu (yere ya da tavana baktığını) sesle
  söylüyor.

### Faz 5: Kurumda yayın

- [ ] **5.1 Beacon yönetimi** (O). **Kod hazır:** Dosyalar → Düzenle →
  Beacon durumu; duyulmayanlar, Eddystone-TLM pil voltajı, haritada
  olmayanlar ve kapsama boşlukları. Gerçek beacon'larla denenecek. Kurumdaki beacon'ların listesi, son
  görülme zamanı, zayıf pil uyarısı, yerleştirme önerisi ("bu koridorda
  beacon yok").
- [x] **5.2 Kurulum kılavuzu** (K). `docs/KURULUM.md`. Bir kurumun Umay'ı kurması: beacon
  yerleştirme, harita çıkarma, sunucu, test.
- [ ] **5.3 Kör kullanıcılarla test** (O). En az 5 kişiyle, gerçek bir
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

---

## Riskler

- **Kamerayla konum bulma beklenen doğruluğa ulaşmayabilir.** Birbirinin
  aynısı koridorlar, cam duvarlar ve kalabalık zorlar. Önlem: beacon her zaman
  yedek. 3.3 önce küçük bir alanda kanıtlanır, sonra büyütülür.
- **Telefonun göğüste tutulması gerekir.** Elinde baston olan biri için
  askı şart. Önlem: cepteki mod (adım ve beacon) her zaman çalışır kalır.
- **Pil.** Kamera ve derinlik sürekli açıkken pil hızlı biter. Önlem: 4.4.
- **Sahada doğrulama eksikliği.** Bugüne kadar yazılan konum kodunun hiçbiri
  gerçek binada ölçülmedi. Önlem: kamerayla konum bulma (3.3, 3.4) ve
  kamerayla ilerleme (Faz 4) 0.2 ve 0.3 bitmeden başlamaz. 3.1 ve 3.2
  mevcut parmak izi eşleştirmesini kullanan arayüz işleridir; yazılabilir,
  bitti ölçütleri sahada doğrulanır.

---

## İlerleme kaydı

En yeni üstte.

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
