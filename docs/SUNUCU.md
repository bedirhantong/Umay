# Kurum harita sunucusu

Bu belge, Umay haritalarını telefonlar arasında dosyayla taşımak yerine kendi
ağında bir sunucudan dağıtmak isteyen kurumların bilgi işlem ekibi içindir.
Sunucu isteğe bağlıdır; tek binalık bir kurum için dosyayla aktarım yeterli
olabilir (bkz. [KURULUM.md](KURULUM.md), 7. adım).

Sunucu bu depodaki `server` modülüdür. JDK'nin kendi HTTP sunucusuyla çalışır;
veritabanı, ek kütüphane ya da internet bağlantısı gerektirmez. Haritaları
diskte birer `.umay.json` dosyası olarak tutar.

---

## Nasıl çalışır

- **Okuma:** Kurumun ağındaki telefonlar haritaları listeler ve indirir.
  İstenirse okuma için de bir anahtar (okuma anahtarı) zorunlu tutulur.
- **Yayın:** Haritayı yüklemek ve kaldırmak için yayın anahtarı gerekir. Bu
  anahtar yalnızca haritayı çıkaran ve düzelten personelin telefonuna giden
  derlemede bulunur; ziyaretçi telefonları yalnızca okuyabilir.
- **Denetim:** Sunucu, yüklenen her haritayı kaydetmeden önce denetler: bozuk,
  tutarsız, 5 MB'tan büyük ya da daha yeni bir Umay sürümüyle yapılmış
  haritaları reddeder.
- **Gizlilik:** Uygulama sunucuya yalnızca haritaları sorar ve yükler. Konum,
  ses, kamera görüntüsü ya da kullanıcıyla ilgili hiçbir şey gönderilmez.
  Sunucu da kimseyi kaydetmez; erişim günlüğü tutmaz.

---

## Kurulum

### 1. Sertifika

Android, şifresiz (HTTP) bağlantıya izin vermez; sunucu HTTPS ile çalışmalıdır.
Sertifika iki şekilde olabilir:

- **Kurumun mevcut sertifikası** (genel bir sertifika otoritesinden): uygulamaya
  ayrıca bir şey vermek gerekmez.
- **Kurumun kendi sertifika otoritesi:** CA sertifikası (PEM) uygulamayı
  derlerken verilir (aşağıya bakın). Uygulama bu CA'ya yalnızca sunucu için
  güvenir.

Sunucu, sertifikasını ve özel anahtarını bir PKCS#12 dosyasından okur.
Örneğin elinizde `sunucu.crt` ve `sunucu.key` varsa:

```sh
openssl pkcs12 -export -in sunucu.crt -inkey sunucu.key -out umay-sunucu.p12
```

Alternatif olarak sunucuyu kurumun mevcut ters vekil sunucusunun (nginx gibi)
arkasında düz HTTP ile çalıştırabilirsiniz (`UMAY_PLAIN_HTTP=1`). Bu durumda
TLS'i vekil sunucu sağlar; sunucunun portunu dış ağa açmayın.

### 2. Derleme ve çalıştırma

JDK 17 ya da üstü gerekir.

```sh
./gradlew :server:installDist
# Çıktı: server/build/install/umay-server/
```

Sunucu ayarlarını ortam değişkenlerinden okur:

| Değişken | Zorunlu | Anlamı |
|---|---|---|
| `UMAY_PUBLISH_TOKEN` | Evet | Yayın anahtarı, en az 16 karakter |
| `UMAY_READ_TOKEN` | Hayır | Varsa okuma için de gerekir, en az 16 karakter |
| `UMAY_DATA_DIR` | Hayır | Haritaların klasörü (varsayılan `./umay-maps`) |
| `UMAY_PORT` | Hayır | Varsayılan 8443 |
| `UMAY_KEYSTORE` | HTTPS için | PKCS#12 dosyasının yolu |
| `UMAY_KEYSTORE_PASSWORD` | HTTPS için | Dosyanın parolası |
| `UMAY_PLAIN_HTTP` | Hayır | `1`: TLS yok, yalnızca TLS sağlayan bir vekilin arkasında |

Anahtarları rastgele üretin, örneğin `openssl rand -hex 24`.

```sh
export UMAY_PUBLISH_TOKEN=$(openssl rand -hex 24)
export UMAY_READ_TOKEN=$(openssl rand -hex 24)
export UMAY_KEYSTORE=/etc/umay/umay-sunucu.p12
export UMAY_KEYSTORE_PASSWORD=...
export UMAY_DATA_DIR=/var/lib/umay
server/build/install/umay-server/bin/umay-server
```

### 3. Sürekli çalışması için (Linux, systemd)

```ini
# /etc/systemd/system/umay.service
[Unit]
Description=Umay harita sunucusu
After=network.target

[Service]
User=umay
EnvironmentFile=/etc/umay/umay.env
ExecStart=/opt/umay-server/bin/umay-server
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

`/etc/umay/umay.env` dosyasına yukarıdaki değişkenleri yazın ve dosyayı
yalnızca `umay` kullanıcısının okuyabileceği şekilde ayarlayın
(`chmod 600`). Haritaların klasörünü kurumun yedekleme düzenine ekleyin.

---

## Uygulamayı sunucuya bağlamak

Sunucu adresi ve anahtarlar, kurum sürümü derlenirken verilir. Biri verilmezse
kurum sürümünün internet izni hiç olmaz; verilirse uygulama yalnızca o adrese
bağlanabilir. Bu derleme sırasında denetlenir.

```sh
# Personel telefonları için (yükleyip kaldırabilir):
./gradlew :app:assembleInstitutionRelease \
    -Pumay.institution.serverUrl=https://harita.kurum.local:8443 \
    -Pumay.institution.readToken=... \
    -Pumay.institution.publishToken=...

# Ziyaretçi telefonları için (yalnızca okur): yayın anahtarı olmadan aynısı.
```

Kurumun kendi CA'sı varsa: `-Pumay.institution.serverCa=/yol/kurum-ca.pem`.

Aynı ayarlar ortam değişkenleriyle de verilebilir:
`UMAY_INSTITUTION_SERVER_URL`, `UMAY_INSTITUTION_READ_TOKEN`,
`UMAY_INSTITUTION_PUBLISH_TOKEN`, `UMAY_INSTITUTION_SERVER_CA`.

Anahtarlar APK'nın içinde bulunur. APK'yı kurum dışına vermeyin; personel APK'sı
yalnızca personel telefonlarına kurulmalıdır. Bir anahtar sızarsa sunucuda
değiştirip uygulamayı yeni anahtarla derleyin.

Uygulamada sunucu **Dosyalar → Kurum harita sunucusu** bölümünde görünür:
haritalar listelenir, indirilir; personel telefonunda yüklenir ve kaldırılır.

---

## Protokol

Taban adres: `https://<sunucu>/v1/maps`. Anahtarlar
`Authorization: Bearer <anahtar>` başlığıyla gönderilir.

| İstek | Anahtar | Yanıt |
|---|---|---|
| `GET /v1/maps` | okuma (varsa) | `200` ve harita özetlerinin listesi (JSON) |
| `GET /v1/maps/{id}` | okuma (varsa) | `200` ve haritanın kendisi (`.umay.json`), yoksa `404` |
| `PUT /v1/maps/{id}` | yayın | `200` ve kimlik; bozuksa `400`/`422`, büyükse `413` |
| `DELETE /v1/maps/{id}` | yayın | `204`, yoksa `404` |
| Anahtar yanlış ya da eksik | | `401` |

Özet alanları: `id`, `displayName` (bina adı), `floorCount`, `nodeCount`,
`sizeBytes`, `updatedAtMs`.

Bir haritanın kimliği bina adından türetilir: adın güvenli karakterleri, `-`
ve adın SHA-256 özetinin ilk 8 onaltılık hanesi (`InstitutionMapIds`). `PUT`
isteğindeki kimlik haritanın adından türetilenle aynı olmalıdır.
