# Site tasarımı: kararlar ve gerekçeleri

Bu dosya, sitenin neden böyle göründüğünü kayda geçirir. Siteye dokunan biri (ya da Claude) önce
buraya bakmalı. Kurallar kısa tutuldu; "güzel tipografi kullan" gibi belirsiz değil, denetlenebilir.

## Kaynaklar

Yayımlanmış bir akademik literatür yok; kaynaklar üretici rehberleri ve uygulayıcı yazılarıdır. Bu yüzden
kanıt düzeyi düşüktür: "işe yarar" iddialarının çoğu yazarların kendi denemelerine dayanır.

- Anthropic, [Improving frontend design through Skills](https://claude.com/blog/improving-frontend-design-through-skills):
  Claude'un sıradan tasarım üretmesinin sebebi "distributional convergence" (modelin eğitim verisindeki en olası
  kalıplara yaslanması: Inter, mor gradyan, aynı kart takımı). Çare, boyut boyut (tipografi, renk, hareket, zemin) yönlendirmek.
- Anthropic, [Prompting for frontend aesthetics](https://platform.claude.com/cookbook/coding-prompting-for-frontend-aesthetics):
  üç strateji: tasarım boyutlarını tek tek yönlendir, bir referans ver, sık varsayılanları açıkça yasakla.
- Anthropic, [frontend-design SKILL.md](https://github.com/anthropics/claude-code/blob/main/plugins/frontend-design/skills/frontend-design/SKILL.md):
  süreç **plan, gözden geçir, kur, ekran görüntüsüyle eleştir**; 4 - 6 adlı renk; 1 - 2 yazı ailesi; "cesareti tek yerde harca";
  yasak listesi (her başlığın üstünde büyük harfli etiket, her karta hover, aynı kart takımı, her bölümde kaydırınca belirme).
- Justin Wetch, [Improving Anthropic's Frontend Design Skill](https://www.justinwetch.com/blog/improvingclaudefrontend/):
  talimatlar Claude'un o konuşmada eyleme dökebileceği biçimde yazılmalı. Yazarın kendi değerlendirmesi (50 istem, hakem olarak Claude)
  zayıf kanıttır; yalnızca fikir olarak alındı.
- AIDesigner, [How to Design Beautiful UIs With Claude Code](https://www.aidesigner.ai/blog/claude-code-frontend-design):
  belirsiz sözler ("modern, temiz") yerine somut kurallar; görsel referans; iki tur ekran görüntüsü karşılaştırması.
  "İki turda farkın %95'i kapanır" iddiası ölçülmemiş bir anekdottur.
- W3C, [WCAG 2.2](https://www.w3.org/TR/WCAG22/): bu sitenin kitlesi görme engelli kişiler ve onlarla çalışanlar; erişilebilirlik
  süsleme değil ölçüttür (AA kontrast, görünür odak, anlamsal işaretleme, hareket azaltma, metin büyütme).

## Bir önceki sürümün eleştirisi

Aynı kaynakların yasak listesine göre önceki tasarımım şunları yapıyordu: her başlığın üstünde büyük harfli, harf aralıklı etiket;
aynı tek tip "kart takımı"; koyu lacivert zeminde ızgara deseni (SaaS kalıbı); hero'da sürekli dönen üç ayrı animasyon. Yani "kurumsal"
görünüyordu ama Umay'a özgü değildi.

## Fikir

Umay'ın dünyasındaki en karakteristik şey **bina içi yön levhaları ve kat planlarıdır**. Sayfanın kendisi bir kat planı gibi kurulur:
hero'da plan çizilir, kırmızı rota bir kez çizilerek hedefe varır. Başka her şey sakin kalır. Yazı tipi olarak, düşük görüşlü okurlar için
Braille Enstitüsü'nün tasarladığı **Atkinson Hyperlegible Next** kullanılır: tema ile birebir örtüşür.

## Belirteçler (`css/site.css` başında)

| Ad | Açık | Koyu | Kullanım |
|---|---|---|---|
| ink | #0E1B2E | #E8EEF7 | Metin |
| navy | #1C4E8C | #8DB3EA | Bağlantı, düğme, koyu bantlar |
| paper | #FFFFFF | #0B1424 | Zemin |
| wash | #EEF3FA | #111E33 | Tablo ve plan zemini |
| ink-soft | #3F5068 | #B4C3D8 | İkincil metin |
| line | #C9D5E6 | #2A3A55 | İnce ayırıcı çizgiler |
| line-strong | #6F87AB | #4F6688 | Plan çizgileri, tablo kenarları |
| signal | #D6483A | #F0705F | **Yalnızca** rota, hedef, uyarı çizgisi. Metin rengi değildir (beyaz üzerinde 4,5:1 altında) |

## Ölçülen kontrast (WCAG göreli parlaklık formülüyle, betikle hesaplandı)

Metin çiftlerinin en düşüğü 6,25:1 (bant üstündeki açık mavi metin); AA eşiği 4,5:1. Açık tema: ink/paper 17,3; ink-soft/paper 8,2;
navy/paper 8,3; beyaz/navy 8,3. Koyu tema: ink/paper 15,8; ink-soft/paper 10,3; navy/paper 8,6. Grafik öğe olan `signal` beyaz
üzerinde 4,3:1 (grafikler için eşik 3:1), bu yüzden metin rengi olmaz. Ölçülmeyenler: gerçek ekran okuyucu (TalkBack, VoiceOver, NVDA)
denemesi ve 200% metin büyütmesi. Bunlar elle yapılmalı.

## Kurallar

1. Tek yazı ailesi (Atkinson Hyperlegible Next, değişken ağırlık), kendi sunucumuzda; dış yazı tipi isteği yok.
2. Boyut sıçraması en az 3 kat: gövde 1,125 rem, ana başlık 2,9 rem ile 5,4 rem arası.
3. Satır uzunluğu 70 karakteri geçmez.
4. Etiketler cümle düzeninde yazılır; büyük harfli etiket ve başlık üstü "eyebrow" yok. Düğmelere ok eklenmez.
5. Hareket: tek bir sayfa yükleme anı (rota çizilir). Kaydırmayla belirme yok, kartlara hover yok. `prefers-reduced-motion` altında hiçbir şey hareket etmez.
6. Kart yığını yok: her bölümün biçimi içeriğe uyar (tanım listesi, tablo, sıralı adımlar).
7. Numara yalnızca gerçekten sıralı içerikte (nasıl çalışır, kurumlar için üç adım).
8. JavaScript yok. Üçüncü taraf isteği yok. Toplam ağırlık 500 KB altında kalmalı.
9. Her etkileşimli öğe klavye ile erişilebilir, görünür odak halkası var, dokunma hedefi en az 44 piksel.
10. Metin rengi hiçbir zaman `signal` değildir. Bilgi yalnızca renkle verilmez.

## Yeni içerik eklerken

Önce hangi biçimin içeriğe uyduğuna karar verin (liste, tablo, adım). "Ben de bir kart ekleyeyim" diye başlamayın.
