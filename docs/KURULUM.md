# Umay'ı bir binada kurmak

Bu kılavuz, Umay'ı kendi binasında görme engelli ziyaretçiler ve çalışanlar
için kurmak isteyen bir kurumun bina sorumlusu ve bilgi işlem ekibi içindir.
Bir binanın kurulumu genellikle bir iki gün sürer: beacon'ları takmak,
haritayı çıkarmak, denemek ve haritayı kullanıcı telefonlarına dağıtmak.

Veri gizliliği için [GIZLILIK.md](GIZLILIK.md) dosyasına bakın.

> Umay bir yardımcıdır; beyaz baston ya da rehber köpeğin yerini tutmaz.
> Kullanıcılara bunu kurulumun her aşamasında açıkça söyleyin.

---

## 1. Hangi sürüm?

Kurumlar için **kurum sürümü** ("Umay Kurum") önerilir: internete hiç
bağlanmaz, Firebase içermez, yedeklenmez. Haritalar telefonlar arasında
dosyayla taşınır.

```sh
./gradlew :app:assembleInstitutionRelease
```

APK'yı kurumun kendi imza anahtarıyla imzalayın (bkz. `app/build.gradle.kts`
başındaki açıklama) ve telefonlara kurumun mobil cihaz yönetimiyle (MDM) ya da
doğrudan kurun. Kurum sürümü Play Store sürümünün yanına ayrı bir uygulama
olarak kurulur.

---

## 2. Beacon'lar

Umay'ın bina içinde konumu düzeltmesi için Bluetooth beacon gerekir. Beacon'sız
bir binada yönlendirme yalnızca adım saymaya dayanır ve uzun düz koridorlarda
metrelerce kayar.

### Hangi beacon?

- **iBeacon** yayını yapan herhangi bir beacon. Umay yalnızca iBeacon'ları
  konum için kullanır.
- Binadaki bütün beacon'lar **aynı UUID**'yi, farklı major/minor değerlerini
  kullanmalı. Telefon ekranı kapalıyken Android yalnızca filtreli taramaya
  izin verir; aynı UUID tek bir filtreyle bulunur. 16'dan fazla farklı UUID
  ya da adres olursa filtre bırakılır ve ekran kapalıyken konum düzeltmesi durur.
- Mümkünse **Eddystone-TLM** de yayınlayan bir model seçin: pil voltajı
  Umay'ın "Beacon durumu" ekranında görünür, biten pil önceden fark edilir.
- Yayın aralığı: **100–300 ms**. Gönderim gücü: orta düzey (üreticinin
  10–15 m menziline denk gelen ayar).

### Nereye?

- Koridorlarda **8–10 metrede bir**, duvarın üst kısmına (yerden 2–2,5 m),
  kalabalığın arasında kalmayacak şekilde.
- Her **kavşakta**, her **asansör ve merdiven** önünde (her katta), sık
  gidilen odaların kapısında.
- Metal dolap, asansör kapısı ya da kalın beton kolon üzerine değil; bunlar
  sinyali keser ve yansıtır.
- Hedef: binanın yürünen her yerinde **en az üç beacon** duyulsun. Harita
  çıkarıldıktan sonra Umay bunu kendisi denetler (bkz. 5. adım).

Beacon'ları taktıktan sonra yerlerini bir daha değiştirmeyin. Bir beacon
yerinden oynarsa haritanın o bölgesi yeniden çıkarılmalıdır.

---

## 3. Haritayı çıkarmak

Haritayı gören bir kişi, binada bir kez yürüyerek çıkarır. Haritayı çıkarırken
**Beacon Tarayıcı** sekmesi kullanılır.

### Hazırlık

- Haritayı çıkaracak telefon **"Google Play Services for AR"** (ARCore)
  destekliyorsa "Kamerayla" modunu kullanın: yol santimetre düzeyinde ölçülür,
  duvarlar çizilir, köşeler kendiliğinden eklenir. Kurum hiçbir Google AR
  bileşeni istemiyorsa "Adım sayarak" modunu kullanın.
- Adım sayarak çıkaracaksanız önce **Ayarlar → Adım uzunluğu** ile adımınızı
  ölçün; haritanın bütün mesafeleri buna dayanır.
- Bina olabildiğince boş olsun (sabah erken ya da mesai sonrası). Kapıları
  açık bırakmak duvar haritasını iyileştirir; kapalıysa kapıyı "Kapı" olarak
  işaretlemek yeterlidir, Umay duvarda açıklığı kendisi açar.

### Yürüyüş

1. Girişte "Haritalamayı başlat"a basın.
2. Kamerayla çıkarıyorsanız telefonu göğüs hizasında, arka kamerası önünüze
   bakacak şekilde tutun. Yavaş ve sabit yürüyün.
3. Önemli bir yere geldiğinizde adını yazıp işaretleyin: "Ana giriş",
   "Danışma", "203 Sınıfı", "Erkek tuvaleti". Kullanıcılar bu adları
   duyacak ve listede seçecek: kısa, açık ve herkesin kullandığı adlar seçin.
4. Kapı, merdiven, asansör ve rampaları türüyle işaretleyin. Kamera bunları
   tanımaz; yönlendirmedeki uyarılar yalnızca burada işaretlenenler içindir.
5. Bir **kavşağa ya da merdivene geri döndüğünüzde** ona ilk verdiğiniz adın
   aynısını tekrar verin. Umay ikisini tek yer sayar ve koridorları orada
   birleştirir. Farklı ad verirseniz koridorlar birleşmez.
6. Kat değiştirirken merdivenin ya da asansörün iki ucunu ayrı ayrı
   işaretleyin ("Doğu merdiveni zemin", "Doğu merdiveni 1. kat"). Telefonda
   basınç sensörü varsa kat kendiliğinden değişir; yoksa +/− ile değiştirin.
7. Binanın kullanıcıların gideceği her koridorunu yürüyün. Yürünmeyen bir
   koridor haritada yoktur.
8. Bitince "Kaydı durdur"a basın, binaya bir ad verip kaydedin.

Kayıt sırasında başka bir uygulamaya geçmek kaydı bozmaz. Uygulama kapansa
bile yürüyüş taslak olarak saklanır ve açılışta geri gelir.

---

## 4. Haritayı düzeltmek

**Dosyalar → (bina) → Düzenle** ile binayı yeniden yürümeden:

- yanlış yazılmış bir adı ya da yanlış türü düzeltin,
- fazladan bir yeri silin,
- duvardan geçen hatalı bir bağlantıyı kaldırın, yürünmüş ama birleşmemiş iki
  yeri bağlayın,
- duvar haritasında yürüyüp geçen birinin bıraktığı lekeyi silin.

Düzenleyici, bir değişiklik bir yeri ulaşılmaz bıraktığında bunu söyler.

---

## 5. Beacon'ları denetlemek

**Dosyalar → (bina) → Düzenle → Beacon durumu** ekranını açıp binada
dolaşın. Ekran şunları gösterir:

- haritanın kullandığı ama duyulmayan beacon'lar (yerinden çıkmış, bozulmuş
  ya da pili bitmiş),
- pili zayıf beacon'lar (yalnızca TLM yayınlayanlarda),
- haritadan sonra takılmış, haritada olmayan beacon'lar,
- **kapsama:** yolun hangi kısmında üçten az beacon duyulduğu, en yakın yerin
  adıyla. Bu yerlere beacon ekleyip haritanın o bölgesini yeniden çıkarın.

Bu denetimi kurulumdan sonra ve sonra her ay tekrarlayın.

---

## 6. Denemek

Haritayı kullanıcılara vermeden önce sahada deneyin:

1. **Dosyalar → Navigasyon sırasında kayıt tut**'u açın.
2. Binadaki birkaç rotayı yönlendirmeyle yürüyün. İsimli bir yerin tam
   üstünden geçerken **"Buradayım"** düğmesine basın; bu, Umay'ın o an
   yaptığı konum hatasını ölçer.
3. Kayıtları **Dosyalar → Saha kayıtları**'ndan paylaşın. Geliştiriciler bu
   kayıtlarla konum ayarlarını binaya göre yapar (bkz. README, "Saha testi").
4. Mümkünse ilk denemeleri görme engelli kullanıcılarla, yanlarında gören bir
   refakatçiyle yapın.

---

## 7. Haritayı dağıtmak

- **Dosyalar → (bina) → Paylaş** haritayı bir `.umay.json` dosyası olarak
  verir. Dosyayı kurumun e-postası, MDM'si ya da USB ile kullanıcı
  telefonlarına aktarın; telefonda dosyaya dokunmak haritayı Umay'a yükler.
- Harita değiştiğinde (yeni beacon, yeni oda, düzeltme) dosyayı yeniden
  dağıtın. Aynı adlı bir bina varsa Umay üzerine yazmadan önce sorar.

---

## 8. Kullanıcıya anlatılacaklar

- Umay'ı açıp binayı, bulunduğu yeri ve gideceği yeri seçmek. Beacon'lar
  duyulunca Umay bulunduğu yeri kendisi önerir: "Danışma yakınında gibi
  görünüyorsun, buradan mı başlıyorsun?"
- Telefon cepteyken de yönlendirme sürer; insan uyarısı için telefonun
  göğüs hizasında, kamerası önüne bakarak tutulması gerekir.
- Telefonda internetsiz Türkçe konuşma sesi yüklü olmalı. Umay eksikse bunu
  söyler ve yükleme ekranını açar.
- Umay bir yardımcıdır; baston ya da rehber köpeğin yerini tutmaz.
