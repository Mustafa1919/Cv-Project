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
| `deploy/local/` | Yereldeki bağımlılıklar (PostgreSQL, Redis) |
| `docs/vitrin/` | Tasarım dokümanları ve karar kayıtları |

## Gerekenler

- Java 25 kurulu olmak zorunda değildir; Gradle eksikse kendisi indirir. Gradle'ı başlatmak için
  makinede herhangi bir Java 17 ya da üstü yeterlidir.
- Docker (yereldeki bağımlılıklar ve ileride entegrasyon testleri için).

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

## Yerelde çalıştırma

Bağımlılıklar:

```
docker compose -f deploy/local/compose.yaml up -d
```

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
