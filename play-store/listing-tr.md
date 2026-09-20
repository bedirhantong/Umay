# Google Play mağaza girişi: Türkçe (varsayılan dil)

Sınırlar (Play Console): uygulama adı en çok 30, kısa açıklama en çok 80, tam açıklama en çok 4000 karakter.
Metinlerde emoji yoktur. Karakter sayıları `play-store/check_listing.py` ile denetlenir.

## Uygulama adı

Umay: Kapalı Alan Asistanı

## Kısa açıklama

Görme engelliler için bina içinde sesli ve titreşimli yol gösteren asistan

## Tam açıklama

Umay, görme engelli kişilerin binaların içinde yolunu bulmasına yardımcı olan, açık kaynaklı bir yönlendirme asistanıdır. GPS'in çalışmadığı kapalı alanlarda sesli yönergeler ve titreşim desenleriyle hedefe götürür.

ÖNEMLİ GÜVENLİK NOTU
Umay bir yardımcıdır, bir hareket aracı değildir. Beyaz baston ya da rehber köpeğin yerini tutmaz. Kamera yalnızca önünüzdeki insanları algılar ve bir insanı kaçırabilir. Merdiven, kapı ve rampa gibi yerleri ancak binanın haritasını çıkaran kişi işaretlediyse bilir. Konum tahmini ara sıra birkaç metre sapabilir. Yolda her zaman kendi dikkatinize ve bastonunuza güvenin.

BİNADA BLUETOOTH BEACON GEREKİR
Umay, haritası çıkarılmış binalarda çalışır. Güvenilir konum için binada Bluetooth işaretçileri (beacon) bulunmalıdır. Beacon olmayan bir yerde uygulama yalnızca adım sayabilir ve konum zamanla sapar. Bir binanın haritası, Beacon Tarayıcı ekranıyla binada bir kez yürünerek çıkarılır.

NASIL ÇALIŞIR
Binalarda GPS çalışmadığı için Umay konumu iki kaynaktan birleştirir:
- Adım sayma: Adımlar telefonun ivmeölçer ve jiroskobundan hesaplanır. Pusula, binadaki çelik ve kablolar yüzünden güvenilmez olduğu için kullanılmaz.
- Bluetooth parmak izi: Haritalanan noktalardaki beacon sinyalleri, adım saymanın zamanla biriken sapmasını düzeltir.

ÖZELLİKLER
- Sesli yönergeler: Hedefe adım adım, Türkçe konuşma sesiyle götürür. Konuşma hızı ayarlanır. Son yönerge tek dokunuşla tekrarlanır.
- Titreşim desenleri: Her yönerge türünün kendine ait bir deseni vardır. Ayarlardaki rehberden hepsini önceden deneyebilirsiniz.
- Kamerayla insan uyarısı: Telefonun kamerası hemen önünüzdeki insanları algılar ve uyarır. Görüntü cihazda işlenir, kaydedilmez.
- Katlar arası yönlendirme: Merdiven ve asansör dahil katlar arasında rota bulur. Telefonda basınç sensörü (barometre) varsa kat değişimi kendiliğinden algılanır.
- Çevredeki yazıyı oku: Tabelalardaki yazıyı cihaz üzerinde okuyup söyler.
- Bir şey sor: Sesle sorduğunuz soruları, o binanın kendi haritasına göre yanıtlar.
- Ekran kapalıyken de: Telefon cebinizdeyken yönlendirme sürer. Ekran kapalıyken kamera çalışamaz; konum ve yönergeler devam eder.
- Büyük ve sabit konumlu dokunma alanları ile ekrana bakmadan kullanım düşünülerek tasarlanmıştır.

HARİTA PAYLAŞIMI
Çıkardığınız haritayı dosya olarak başka cihazlara aktarabilirsiniz. İsterseniz haritayı paylaşımlı havuza ekleyerek aynı binaya giden başkalarının binayı yeniden taramasını önleyebilirsiniz. Havuza yüklenen harita herkes tarafından görülebilir; paylaşımı istediğiniz zaman kaldırabilirsiniz.

GİZLİLİK
- Hesap açmanız gerekmez.
- Kamera görüntüsü kaydedilmez, saklanmaz ve gönderilmez.
- GPS konumunuz toplanmaz.
- Reklam ve kullanım analizi yoktur.
- Haritalar, ayarlar ve kayıtlar cihazınızda kalır. Cihazdan yalnızca siz bir haritayı paylaştığınızda ya da bir dosyayı kendiniz gönderdiğinizde çıkar.

İZİNLER
- Kamera: Önünüzdeki insanları algılamak ve tabelaları okumak için.
- Konum ve Bluetooth: Android, Bluetooth işaretçilerini taramak için konum iznini şart koşar. Umay GPS konumunuzu kullanmaz.
- Mikrofon: Yalnızca "Bir şey sor" düğmesine bastığınızda sorunuzu dinlemek için. Ses kaydedilmez.
- Bildirim: Yönlendirme sürerken ekran kapalıyken çalışabilmek için.

AÇIK KAYNAK
Umay, GNU Genel Kamu Lisansı sürüm 3 (GPL-3.0) ile dağıtılan özgür yazılımdır. Kaynak kodunu inceleyebilir, değiştirebilir ve paylaşabilirsiniz. Hata bildirimi, çeviri, test ve kod katkıları için proje sayfasına bakın.

Uygulamanın arayüzü ve sesli yönergeleri Türkçedir. Türkçe konuşma sesi cihazınızda yüklü değilse ayarlardan yükleyebilirsiniz.

Gizlilik politikası, destek ve kaynak kodu: proje web sitesinde ve GitHub'da (bedirhantong/Umay).
