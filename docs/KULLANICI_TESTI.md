# Görme engelli kullanıcılarla test protokolü

Bu belge, Umay'ı görme engelli kullanıcılarla gerçek bir binada deneyecek ekip
içindir (geliştirici, kurum sorumlusu, refakatçiler). Amaç, Umay'ın kullanıcıyı
güvenle ve anlaşılır biçimde bir yerden başka bir yere götürüp götürmediğini
ölçmek ve neyin düzeltilmesi gerektiğini bulmaktır.

Bir test günü 5 katılımcıyla yaklaşık bir gün sürer (katılımcı başına 60–75
dakika).

---

## 1. Güvenlik ve onam

- Her katılımcının yanında **her an gören bir refakatçi** bulunur. Refakatçi
  yalnızca tehlike anında müdahale eder ve her müdahaleyi kaydeder.
- Katılımcı bastonunu ya da rehber köpeğini her zamanki gibi kullanır. Umay
  bunların yerini tutmaz; bu, teste başlamadan sözlü olarak söylenir.
- Merdiven görevlerinde refakatçi bir basamak geride ve yanda durur.
- Katılımcı istediği an, gerekçe göstermeden testi bırakabilir.
- **Bilgilendirilmiş onam** (KVKK uyarınca) teste başlamadan alınır; metin
  katılımcıya sesli okunur ya da erişilebilir biçimde verilir. Onamda:
  hangi verinin toplandığı (saha kaydı: bina içi konum, adımlar, sinyaller,
  yönergeler; görüntü ve ses yok), nerede saklandığı, ne zaman silineceği ve
  katılımcının haklarının ne olduğu yazar.
- Fotoğraf ya da video çekilecekse ayrıca onay alınır.

---

## 2. Katılımcılar

En az 5 kişi; mümkün olduğunca farklı:

- tamamen görmeyen ve az gören,
- beyaz baston ve rehber köpek kullanan,
- TalkBack kullanan ve kullanmayan,
- binayı tanıyan ve hiç bilmeyen,
- farklı yaşlarda.

Her katılımcı için not edilir: görme durumu, yardımcı araç, telefon ve Android
sürümü, TalkBack kullanımı, binayı tanıyıp tanımadığı. Ad yazılmaz; katılımcılar
K1, K2… diye anılır.

---

## 3. Hazırlık (test gününden önce)

- [ ] Bina haritası çıkarıldı, düzeltildi, "Beacon durumu" ekranında
  duyulmayan ya da pili zayıf beacon yok ve kapsama uyarısı giderildi
  ([KURULUM.md](KURULUM.md)).
- [ ] Yer işaretleri yazdırıldı (15 cm ölçüldü) ve asıldı.
- [ ] Test telefonlarına Umay kuruldu, harita yüklendi; internetsiz Türkçe
  konuşma sesi ve cihaz içi Türkçe konuşma tanıma yüklü.
- [ ] **Dosyalar → Navigasyon sırasında kayıt tut** açık.
- [ ] Görevlerin başlangıç ve hedef yerleri belli, rotalar refakatçilerle
  önceden yüründü.
- [ ] Engel görevi için bir sandalye ve bir kişi hazır.
- [ ] Göğüs askısı (varsa) hazır.

---

## 4. Görevler

Her görevde katılımcıya yalnızca hedef söylenir; nasıl yapılacağı gösterilmez.
Refakatçi süreyi tutar ve aşağıdaki ölçümleri doldurur.

| # | Görev | Ne ölçülür |
|---|---|---|
| G1 | Umay'ı açıp binayı ve bulunduğu yeri listeden seçmek | Yardımsız seçebildi mi, süre, TalkBack'te takıldığı yer |
| G2 | Önerilen başlangıcı kabul etmek ("… yakınında gibi görünüyorsun") | Öneri geldi mi, doğru muydu, anlaşıldı mı |
| G3 | Yer işaretinin olduğu bir yerde **Kamerayla bul** ile başlangıcı bulmak | Bulundu mu, kaç saniyede, katılımcı nasıl döndü |
| G4 | Aynı katta en az iki dönüşlü bir rota | Varış, süre, yanlış dönüş, refakatçi müdahalesi |
| G5 | Kat değiştiren bir rota (merdiven ya da asansör) | Kat geçişi doğru algılandı mı, yönerge anlaşıldı mı |
| G6 | Rota üzerinde konan bir sandalye ve duran bir kişi | Uyarı zamanında geldi mi, doğru adlandırıldı mı, çarpma oldu mu |
| G7 | "Bir şey sor": "Tuvalet nerede?" | Anlaşıldı mı, yanıt doğru muydu |
| G8 | Rotanın bir kısmını telefon cepteyken yürümek | Yönlendirme sürdü mü, konum ne kadar kaydı |

Her görevde refakatçi, isimli bir yerin tam üstünden geçerken uygulamadaki
**Buradayım** düğmesine basar (ya da katılımcıdan bastırmasını ister). Bu,
konum hatasının ölçüsüdür.

---

## 5. Ölçümler

Her görev için:

- **Başarı:** yardımsız / sözlü yardımla / refakatçi müdahalesiyle / başarısız.
- **Süre:** başlangıçtan varışa (saniye).
- **Hatalar:** yanlış dönüş, kaçırılan hedef, yanlış kat.
- **Güvenlik:** çarpma, takılma, refakatçi müdahalesi (her biri, nedeniyle).
- **Konum hatası:** saha kaydındaki "Buradayım" işaretlerinden (otomatik).
- **Yönergeler:** anlaşılmayan, geç gelen ya da yanlış bir cümle (aynen yazılır).

Testin sonunda:

- **Sistem Kullanılabilirlik Ölçeği (SUS):** 10 soru, sesli okunarak, 1–5
  arası yanıt.
- **Açık sorular:** En çok neye güvendiniz, neye güvenmediniz? Hangi cümle
  kafanızı karıştırdı? Titreşimler anlaşılır mıydı? Kendi başınıza kullanır
  mıydınız, hangi binada? Eksik olan ne?

---

## 6. Veriler

- Saha kayıtları **Dosyalar → Saha kayıtları → Tümünü paylaş** ile alınır ve
  K1, K2… diye adlandırılır.
- Kayıtlarda ad, yüz ya da ses yoktur; yalnızca bina içi konum, adımlar, sinyal
  güçleri, basınç ve yönergeler vardır.
- Notlar ve kayıtlar onamda yazan süre sonunda silinir.
- Her kaydın ortalama ve en kötü konum hatası, hangi yerde olduğuyla
  **Dosyalar → Saha kayıtları**'nda kaydın altında görünür: göndermeden
  sahada bakılabilir.
- Kayıtlar geliştiriciye verilir; konum ayarları bu kayıtlarla yapılır
  (`FieldLogReplayTest`, README "Saha testi").

---

## 7. Rapor şablonu

```
Test tarihi, bina, katılımcı sayısı, telefon modelleri

Özet
- Görev başarısı (G1–G8): yardımsız / yardımla / başarısız sayıları
- Ortalama konum hatası (Buradayım): … m, en kötü: … m
- Güvenlik olayları: …
- SUS ortalaması: …

Görev görev bulgular
- G4: K2 ikinci dönüşü kaçırdı; yönerge "… sonra sağa dön" dönüşten 6 m
  sonra geldi (kayıt K2, 14:05).
- …

Önceliklendirilmiş düzeltmeler
1. (güvenlik) …
2. (anlaşılırlık) …
3. …
```

Rapor, `docs/YOL_HARITASI.md` ilerleme kaydına özetle işlenir; düzeltmeler
plana iş olarak eklenir.
