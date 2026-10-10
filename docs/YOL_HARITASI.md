# Umay yol haritası

Bu dosya Umay'ın nereye gittiğini ve oraya hangi sırayla varacağını tutar.
Yaşayan bir belgedir: bir iş bittiğinde kutusu işaretlenir, en alttaki
**İlerleme kaydı**'na tarihli bir satır eklenir. Yeni bir iş bu plana
uymuyorsa önce plan güncellenir.

Son güncelleme: 10 Ekim 2026

---

## Hedef

Görme engelli bir kişi, bir kurumun binasında (beacon'lı) ya da kendi evinde,
**hiçbir veriyi cihazdan veya kurumun kendi sisteminden dışarı çıkarmadan**
bir yerden başka bir yere kendi başına gidebilsin.

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
| Kamerayla konum bulma | Yok | |
| Derinlikle engel uyarısı (sandalye, masa, duvar) | Yok | |
| Duvar haritası üzerinde serbest rota (koltuktan başlamak) | Yok | |
| Kapıların harita parçası olması | Yok (yalnızca nokta) | |
| Kurum sürümü (Firebase'siz, ağ kısıtlı) | Yok | |

---

## Fazlar

Büyüklükler kabadır: **K** birkaç gün, **O** bir iki hafta, **B** birkaç
hafta ve üstü. "Bitti" ölçütü karşılanmadan kutu işaretlenmez.

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
- [ ] **0.5 Bilinen hata: adım modunda kayda devam edince yol baştan
  başlıyor** (K). Durdurup "Kayda devam et" denince adım sayımı (0, 0)'dan
  yeniden başlıyor.
  *Bitti:* devam eden yürüyüş kaldığı noktadan sürüyor, testle korunuyor.
- [ ] **0.6 Branch'leri birleştir** (K). `feat/graph-particle-filter` ve
  `feat/ar-survey` için `development`'a PR açıldı ve birleştirildi.

### Faz 1: Gizlilik ve kurum içi çalışma

Kurumla konuşmadan önce kapanması gereken açıklar. Hepsi küçük ve net.

- [ ] **1.1 Konuşma tanıma yalnızca cihazda** (K). `SpeechToText` bugün
  çevrimdışı tanımayı zorlamıyor, ses Google'a gidebilir. Android 12+ için
  cihaz içi tanıyıcı, eski sürümler için `EXTRA_PREFER_OFFLINE` kullanılacak.
  Cihaz içi tanıma yoksa özellik kapalı olacak ve nedeni söylenecek.
  *Bitti:* uçak modunda "Bir şey sor" çalışıyor ya da çalışamayacağını
  söylüyor; ağ açıkken de ses cihazdan çıkmıyor.
- [ ] **1.2 ARCore açıklaması** (K). Google'ın kuralı gereği, ARCore'un
  Google tarafından sağlandığı ve Google Gizlilik Politikası'na tabi olduğu
  uygulama içinde görünür şekilde söyleniyor (Ayarlar → Hakkında ve
  kamerayla haritalama ekranı).
  *Bitti:* metin iki yerde de var ve TalkBack ile okunuyor.
- [ ] **1.3 Kurum sürümü** (O). Firebase'siz ayrı bir derleme türü
  (`kurum`): paylaşımlı havuz yok, Firebase kütüphaneleri pakette yok.
  *Bitti:* APK'da Firebase sınıfı yok. Harita aktarımı dosyayla ya da 1.5
  ile yapılıyor.
- [ ] **1.4 Ağ kısıtı** (K). Kurum sürümünde ağ güvenlik yapılandırması
  yalnızca derleme sırasında verilen kurum sunucu adresine izin veriyor.
  Sunucu yoksa uygulamanın internet izni de yok.
  *Bitti:* başka bir adrese istek atılamadığı testle gösterildi.
- [ ] **1.5 Kurum içi harita sunucusu** (O). Kurumun ağında çalışan basit
  bir sunucu: haritaları listeler, indirir, yetkili cihazdan yükleme kabul
  eder. Uygulama tarafında `RemoteBuildingMapRepository`'nin ikinci bir
  gerçeklemesi. Protokol bu klasörde belgelenir.
  *Bitti:* iki telefon aynı kurum ağında birinin çıkardığı haritayı
  diğerine aktarıyor. İnternet kapalıyken de çalışıyor.
- [ ] **1.6 Gizlilik belgesi** (K). Hangi veri nerede durur, ne zaman silinir,
  hangi Google bileşeni ne yapar. Kurumlar için `docs/GIZLILIK.md`, ayrıca
  Play Store veri güvenliği formu için özet.
  *Bitti:* belge yazıldı, uygulamadaki davranışla birebir.

### Faz 2: Harita, kapılar ve düzenleme

- [ ] **2.1 Kapılar haritanın parçası** (O). Haritayı çıkaran kişi bir
  kapıyı işaretlediğinde, kapı duvar haritasında bir açıklık olarak
  kaydediliyor (konum, genişlik, hangi iki alanı bağladığı). Navigasyon
  haritasında kapı olarak çiziliyor.
  *Bitti:* kapalı bir kapı duvar haritasında duvar gibi görünse bile
  haritada kapı olarak duruyor, rota ondan geçebiliyor.
- [ ] **2.2 Harita format sürümü** (K). `.umay.json`'a bir sürüm numarası
  ekleniyor, eski sürümler okunurken yükseltiliyor.
  *Bitti:* eski haritalar açılıyor, yeni alanlar testle korunuyor.
- [ ] **2.3 Harita düzenleme** (O). Kaydedildikten sonra yer adı ve türü
  değiştirme, yer silme, yanlış bağlantıyı kaldırma, duvar haritasında
  hatalı lekeyi silme. Erişilebilir olmalı (liste tabanlı düzenleme).
  *Bitti:* bir haritadaki hatalar yeniden haritalamadan düzeltiliyor.
- [ ] **2.4 Sık beacon parmak izi** (O). Parmak izi yalnızca isim verilen
  noktalarda değil, kamerayla çıkarılan yol boyunca birkaç metrede bir
  kaydediliyor.
  *Bitti:* parçacık filtresi beacon'ı yalnızca güvenilir eşleşmede değil,
  her taramada yumuşak bir kanıt olarak kullanıyor. Saha kaydında hata
  düşüyor.

### Faz 3: Konum, "neredeyim"

- [ ] **3.1 TalkBack ile başlangıç** (K). "Bulunduğum yer" seçicisi en yakın
  yerleri öne alıyor (beacon varsa). Seçim sesli onaylanıyor.
  *Bitti:* kör bir test kullanıcısı başlangıcı yardımsız seçiyor.
- [ ] **3.2 Beacon ile kendiliğinden başlangıç** (K). Kurumda uygulama
  açılınca en olası yeri öneriyor: "Giriş holündesin gibi görünüyor, doğru
  mu?"
  *Bitti:* test binasında 10 denemenin en az 8'inde doğru yeri öneriyor.
- [ ] **3.3 Kamerayla konum bulma** (B). Robot süpürge yöntemi: yönlendirme
  sırasında ARCore çalışıyor. Derinlik ölçümleri kaydedilmiş duvar
  haritasıyla karşılaştırılarak konum bulunuyor. Duvar haritası üzerinde,
  koridor çizgilerine bağlı olmayan serbest alan parçacık filtresi
  kullanılıyor. Beacon kaba başlangıcı veriyor, kamera ince ayarı yapıyor.
  Hepsi cihazda, Kotlin ile, yeni native kütüphane olmadan.
  *Bitti:* kullanıcı oturduğu yerde telefonu birkaç saniye çevirince konumu
  1 m içinde bulunuyor. Benzer iki koridor karıştığında beacon ayırıyor.
  Sahada ölçüldü.
- [ ] **3.4 Konum kaynaklarının birleşmesi** (O). Kamera varken kamera,
  telefon cebe girince adım ve beacon, kamera geri gelince yeniden kamera.
  Geçişlerde konum zıplamıyor.
  *Bitti:* bir yürüyüş boyunca telefon iki kez cebe girip çıkıyor, saha
  kaydında hata sınırın içinde kalıyor.

### Faz 4: Kamerayla ilerleme

- [ ] **4.1 Derinlikle engel uyarısı** (O). Yönlendirmede önündeki
  sandalye, masa, duvar ve kapalı kapı ölçülüyor, "Önünde 1 metrede engel"
  deniyor. İnsan algılamayla birlikte çalışıyor, uyarılar üst üste binmiyor.
  *Bitti:* test parkurunda bütün engeller uyarıldı, yanlış uyarı az.
- [ ] **4.2 Serbest alanda rota** (O). Duvar haritası üzerinde, duvardan
  güvenli mesafe bırakan yol bulma. Kullanıcı koltuktan, odanın ortasından
  başlayabiliyor. Graf rotasıyla birleşiyor.
  *Bitti:* evde koltuktan mutfağa rota çıkıyor, duvarlara sürtmüyor.
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

- [ ] **5.1 Beacon yönetimi** (O). Kurumdaki beacon'ların listesi, son
  görülme zamanı, zayıf pil uyarısı, yerleştirme önerisi ("bu koridorda
  beacon yok").
- [ ] **5.2 Kurulum kılavuzu** (K). Bir kurumun Umay'ı kurması: beacon
  yerleştirme, harita çıkarma, sunucu, test.
- [ ] **5.3 Kör kullanıcılarla test** (O). En az 5 kişiyle, gerçek bir
  binada, gözlemli. Sonuçlar ve düzeltmeler yazılı.
- [ ] **5.4 Erişilebilirlik denetimi** (K). Bütün ekranlar TalkBack,
  büyük yazı ve yüksek kontrastla denetlendi.
- [ ] **5.5 Mağaza hazırlığı** (K). Veri güvenliği formu, ARCore açıklaması,
  güvenlik uyarıları, 16 KB sayfa boyutu (`libtask_vision_jni.so`, Şubat
  2027 son tarih).

---

## Açık kararlar

Bunlar bir sonraki adımı doğrudan etkiler. Karar verildikçe buraya yazılır.

1. **ARCore kabul edilebilir mi?** ARCore, Google'ın kapalı kaynak "Google
   Play Services for AR" uygulamasıyla çalışır. Kurum hiçbir Google bileşeni
   istemezse kamerayla harita çıkarma ve konum bulma için açık kaynak bir
   görsel takip çözümü gerekir (ORB-SLAM3 veya RTAB-Map). Bu büyük bir iştir
   ve 16 KB sorununu yeniden açar.
2. **Kurum sunucusu mu, dosya aktarımı mı?** Tek binalık bir kurum için
   dosyayla aktarım yetebilir, 1.5 ertelenebilir.
3. **Test binası ve kurum.** Faz 0.3 ve Faz 3 için hangi bina?
4. **Hedef telefonlar.** Kamerayla konum bulma için kullanıcının telefonunda
   ARCore Depth desteği gerekir. Hangi telefonları destekleyeceğiz?
5. **Ev senaryosu.** Evde beacon olmayacak. Ev için yalnızca kamera ve adım
   yeterli mi, yoksa ev ikinci aşamaya mı kalıyor?

---

## Riskler

- **Kamerayla konum bulma beklenen doğruluğa ulaşmayabilir.** Birbirinin
  aynısı koridorlar, cam duvarlar ve kalabalık zorlar. Önlem: beacon her zaman
  yedek. 3.3 önce küçük bir alanda kanıtlanır, sonra büyütülür.
- **Telefonun göğüste tutulması gerekir.** Elinde baston olan biri için
  askı şart. Önlem: cepteki mod (adım ve beacon) her zaman çalışır kalır.
- **Pil.** Kamera ve derinlik sürekli açıkken pil hızlı biter. Önlem: 4.4.
- **Sahada doğrulama eksikliği.** Bugüne kadar yazılan konum kodunun hiçbiri
  gerçek binada ölçülmedi. Önlem: Faz 0 bitmeden Faz 3'e geçilmez.

---

## İlerleme kaydı

En yeni üstte.

- **10 Ekim 2026:** Yol haritası yazıldı. Parçacık filtresi
  (`feat/graph-particle-filter`) ve kamerayla harita çıkarma
  (`feat/ar-survey`) yazıldı ve birim testleri geçiyor. İkisi de sahada ve
  telefonda doğrulanmayı bekliyor (0.2, 0.3). Konuşma tanımanın ses
  verisini cihaz dışına çıkarabildiği (1.1) ve ARCore açıklamasının eksik
  olduğu (1.2) tespit edildi.
