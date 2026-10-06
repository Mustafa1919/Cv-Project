# Vitrin

İşe alım platformu senaryosu üzerinde çalışan, kararlarını ve ölçümlerini kendisi gösteren bir
sistem. Tasarım dokümanları: [`docs/vitrin/README.md`](docs/vitrin/README.md).

Geliştirme sürüyor; yayınlanmış bir sürüm henüz yok.

## Yapı

| Klasör | İçerik |
|---|---|
| `gateway/` | Giriş katmanı |
| `core/` | Modüler monolit; veritabanının tek sahibi |
| `search/` | Okuma modeli servisi |
| `platform/` | Servislerin paylaştığı teknik kod |
| `build-logic/` | Ortak derleme ayarı (Gradle kural eklentileri) |
| `content/schema/` | İçerik şeması (JSON Schema); site ve servisler aynı şemayı kullanır |
| `content/tool/` | İçerik denetim aracı (Node, TypeScript) |
| `site/` | Statik site (Astro) ve tarayıcı testleri |
| `deploy/local/` | Yereldeki bağımlılıklar (PostgreSQL, Redis) |
| `docs/vitrin/` | Tasarım dokümanları ve karar kayıtları |

## Gerekenler

- Java 25 kurulu olmak zorunda değildir; Gradle eksikse kendisi indirir. Gradle'ı başlatmak için
  makinede herhangi bir Java 17 ya da üstü yeterlidir.
- Docker (yereldeki bağımlılıklar ve ileride entegrasyon testleri için).
- Node 22.17 ya da üstü (içerik denetim aracı için).

## İlk kurulum

Depo klonlandıktan sonra bir kez:

```
git config --local user.name  "Ad Soyad"
git config --local user.email "adres@ornek.com"
git config core.hooksPath .githooks
```

Commit öncesi kanca, depoya özel kimlik yoksa ve Java dosyalarında biçim hatası varsa commit'i
durdurur.

## Derleme ve test

```
./gradlew build
```

Bu komut derler, testleri koşar ve şunları denetler; herhangi biri derlemeyi kırar:

- Derleyici uyarıları, Error Prone ve NullAway
- Biçim (Spotless); düzeltmek için `./gradlew spotlessApply`
- SpotBugs
- Koşan test sayısı: her modülün koşturduğu test sayısı `gradle/expected-tests.properties`
  içindeki değerle aynı olmalıdır. Test ekleyen ya da silen commit bu dosyayı da günceller.
- Bağımlılık özetleri: indirilen her dosya `gradle/verification-metadata.xml` içindeki SHA-256
  değeriyle karşılaştırılır.

Bağımlılık eklendiğinde ya da sürüm değiştiğinde özet dosyası yeniden üretilir:

```
./gradlew --write-verification-metadata sha256 build spotlessApply
```

## İçerik denetimi

Profil içeriği `content/` altında YAML dosyalarıdır; biçimi `content/schema/` tanımlar. Denetim
aracı Gradle derlemesinin parçası değildir, `content/tool/` içinden çalışır:

```
npm ci              # bir kez, depo kökünde (npm çalışma alanı)
npm run typecheck   # şemadan tipleri üretir, sonra tip denetimi
npm test            # koşan test sayısı expected-tests.json ile aynı olmalıdır
npm run check       # content/ altındaki içeriği denetler
npm run approve     # iki dili birlikte değişen metinleri content/i18n.lock içine kaydeder
```

`check` şunlarda hata verir: kanıtsız yetenek, hedefi bulunmayan kanıt, bir dilde eksik metin,
arayüz sözlüklerinde anahtar farkı, yalnızca bir dili değişmiş metin (bayat çeviri), bölümü eksik
vaka anlatımı, kaynağı olmayan sayı, seviye ya da yüzde alanı. Yalnızca bir dili değişen metin
bilerek öyle bırakılacaksa `npm run approve -- --accept-one-sided <birim anahtarı>` ile tek tek
onaylanır.

## Site

`site/` içinden. Varsayılan içerik kurgusal örnek içeriktir (`site/sample-content`); gerçek içerik
`VITRIN_CONTENT_DIR` ile verilir.

```
npx playwright install chromium   # bir kez
npm run typecheck
npm run build      # içeriği denetler, derler, güvenlik başlıklarını (dist/_headers) yazar
npm run serve      # dist klasörünü http://127.0.0.1:4173 adresinde sunar
npm test           # derler ve tarayıcı testlerini koşar; sayı expected-tests.json ile aynı olmalıdır
```

| Değişken | Varsayılan | Anlamı |
|---|---|---|
| `VITRIN_SITE_ORIGIN` | `http://localhost:4173` | Sitenin adresi (kanonik bağlantılar, site haritası) |
| `PUBLIC_VITRIN_API_ORIGIN` | `http://localhost:8080` | Durum rozetinin sorduğu API adresi |
| `VITRIN_CONTENT_DIR` | `sample-content` | Derlenecek içerik |
| `VITRIN_NOINDEX` | yok | `1` ise sayfalar dizine girmez (önizleme yayınları) |

## Yerelde çalıştırma

Bağımlılıklar:

```
docker compose -f deploy/local/compose.yaml up -d
```

Makinede kurulu bir PostgreSQL 5432 portunu tutuyorsa başka port verilir
(`VITRIN_POSTGRES_PORT=15432`; Redis için `VITRIN_REDIS_PORT`).

Servisler (her biri ayrı terminalde). Profil vermek zorunludur; profilsiz servis açılmaz:

```
./gradlew :core:bootRun    --args="--spring.profiles.active=dev"
./gradlew :search:bootRun  --args="--spring.profiles.active=dev"
./gradlew :gateway:bootRun --args="--spring.profiles.active=dev"
```

| Servis | Uygulama portu | Yönetim portu |
|---|---|---|
| gateway | 8080 | 9080 |
| core | 8081 | 9081 |
| search | 8082 | 9082 |

Sağlık denetimi yönetim portundadır, örneğin `http://localhost:9081/actuator/health/readiness`.
