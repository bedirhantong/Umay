# Umay web sitesi

Düz HTML ve CSS; derleme adımı, betik ya da üçüncü taraf isteği yoktur. Firebase Hosting ile yayınlanır
(`firebase.json` içindeki `hosting` bölümü, `public` = bu klasör).

Yerelde görmek için herhangi bir statik sunucu yeter (VS Code Live Server dahil); tüm bağlantılar göreli
olduğundan klasör nerede durursa dursun çalışır:

```sh
python3 -m http.server 8080 --directory website
```

Yayınlamak için (Firebase CLI, proje `umay-1e98e`):

```sh
firebase deploy --only hosting
```

Google Play Console'a verilecek adresler: gizlilik politikası `/gizlilik`, veri silme `/veri-silme`,
destek `/destek`. Gizlilik metni uygulamanın gerçek davranışını anlatır; izinler, saha kaydı, paylaşımlı
havuz ya da üçüncü taraf bileşenler değiştiğinde `gizlilik.html` ve `en/privacy.html` de güncellenmelidir.
