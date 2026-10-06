# Cloudflare kurulumu

`alanadi` yer tutucusunu kendi alan adınızla değiştirin. Güncel özellik, sınır ve ücretleri Cloudflare’ın resmi sayfasından kontrol edin.

## Cloudflare’da neler bulunur?

- Statik site için bir **Pages projesi**.
- Docker’daki gateway ve Grafana için bir **Tunnel**.
- Grafana gibi sahip araçları için bir **Access uygulaması**.

SSH için ayrıca sunucuda systemd servisi olarak çalışan ikinci bir `cloudflared` ve ayrı bir tünel gerekir. **Bu SSH kurulumu henüz yapılmadı.**

## Adresler

| Adres | Hedef |
|---|---|
| `alanadi` | Pages projesi |
| `api.alanadi` | Docker tüneli → `http://gateway:8080` |
| `grafana.alanadi` | Access → Docker tüneli → `http://grafana:3000` |
| `ssh.alanadi` | Daha sonra kurulacak ayrı sunucu SSH tüneli |

Docker tüneli izole ağdadır; sunucunun SSH portuna erişebildiği varsayılmaz. Docker tüneline SSH hedefi eklemeyin.

## Kurulum sırası

1. Alan adını Cloudflare’a ekleyin ve kayıt kuruluşunda verilen nameserver’ları kullanın.
2. Pages projesini oluşturun, statik siteyi yayımlayın ve `alanadi` özel alan adını bağlayın.
3. Zero Trust bölümünde Docker için bir tünel oluşturun. Docker çalıştırma seçeneğinden **yalnızca token değerini** alın; tüm komutu kopyalamayın.
4. Token’ı sunucuda root olarak `/opt/vitrin/secrets/tunnel-token` dosyasına güvenli biçimde yazın. Dosya modu `0444`, üst dizin modu `0700` olmalıdır. Token’ı terminal çıktısına, Git’e veya ortam değişkenine koymayın.
5. Depodaki `deploy/server/secrets.sh --check /opt/vitrin/secrets` komutunu root olarak çalıştırın. Eksik dosya veya Redis parola uyuşmazlığı olmamalıdır.
6. Tünelde `api.alanadi` için `http://gateway:8080`, `grafana.alanadi` için `http://grafana:3000` hedeflerini ekleyin. Cloudflare’ın bu adresler için oluşturduğu DNS kayıtlarını doğrulayın.
7. Grafana’yı açmadan önce aşağıdaki Access uygulamasını oluşturun.
8. Sunucunun `COMPOSE_PROFILES` değerinde `edge,observability` bulunduğunu doğrulayın ve üretim stack’ini başlatın. Tünelin bağlantılı, gateway’in sağlıklı olduğunu kontrol edin.

## Zone ayarları

- **Always Use HTTPS:** açık olsun; HTTP isteğinin HTTPS’e yönlendiğini doğrulayın.
- **Minimum TLS sürümü:** `1.2` olsun.
- **HSTS:** sitenin `_headers` dosyasından gelir. Pages yanıtında `Strict-Transport-Security` başlığını doğrulayın; zone üzerinden ikinci bir HSTS politikası eklemeyin.
- `api.alanadi` için cache kuralı eklemeyin. API yanıtlarının yanlışlıkla önbelleğe alınmasına yol açacak genel kurallar kullanmayın.

## Grafana Access politikası

1. `grafana.alanadi` için self-hosted Access uygulaması oluşturun.
2. Allow politikasına yalnızca sahibin **tek e-posta adresini** ekleyin.
3. Herkese açık Allow veya Bypass politikası eklemeyin.
4. Oturum süresini **1 saat** olarak ayarlayın.
5. Gizli tarayıcı penceresinde giriş isteğini, izinli hesabın erişimini ve başka bir hesabın reddedilmesini doğrulayın.

Access, Grafana’nın kendi giriş ekranının yerine geçmez. Grafana yönetici parolası da gereklidir.

## Dışarıdan doğrulama

Kontrolleri sunucudan değil, başka bir internet bağlantısından yapın.

- `dig NS alanadi`  
  Beklenen: Cloudflare’ın zone için verdiği nameserver’lar.
- `dig A alanadi`  
  Beklenen: Pages adresi çözülür; origin sunucusunun adresi değildir.
- `dig A api.alanadi` ve `dig A grafana.alanadi`  
  Beklenen: Cloudflare tarafındaki adresler; origin sunucusunun adresi değildir. Proxy kullanılan kayıtların CNAME hedefi herkese açık DNS yanıtında görünmeyebilir.
- `curl -sI http://alanadi`  
  Beklenen: HTTPS’e yönlendirme.
- `curl -sI https://alanadi`  
  Beklenen: başarılı Pages yanıtı ve `_headers` dosyasındaki HSTS başlığı.
- `curl -sI https://api.alanadi`  
  Beklenen: API’den HTTP yanıtı; Cloudflare tünel bağlantı hatası değil. Kök yol tanımlı değilse 404 veya 405 normal olabilir; backend arızasında gateway 503 verebilir.
- `curl -sI https://grafana.alanadi`  
  Beklenen: kimlik doğrulanmamış istekte Access giriş yönlendirmesi veya erişim reddi; açık Grafana paneli değil.

Origin adresini sağlayıcı panelinden alın ve kendi sunucunuza port taraması yapın:

`read -r -p 'Origin sunucusunun IP adresi: ' SUNUCU_IP; nmap -Pn -sT -p- "$SUNUCU_IP"`

Beklenen: ilk aşamada yalnızca SSH için `22/tcp` erişilebilir; rate limit nedeniyle filtreli görünebilir. Web, Grafana ve gözlemleme portları açık olmamalıdır. SSH kapatıldıktan sonra hiçbir inbound TCP portu açık olmamalıdır. Sunucunun IPv6 adresi varsa aynı kontrolü `nmap -6` ile de yapın.

## SSH’yi kapatmadan önce

1. Sağlayıcının web/seri konsoluna girin ve sunucuya erişebildiğinizi **bir kez gerçekten deneyin**.
2. Sunucuda ikinci `cloudflared` için ayrı SSH tünelini ve systemd servisini kurun; bu katman henüz bunu yapmıyor.
3. `ssh.alanadi` erişimini Access ile koruyun ve dışarıdan SSH bağlantısını doğrulayın.
4. Ancak bundan sonra `setup.sh --close-ssh` çalıştırın.

Script’in Docker tünelini sağlıklı görmesi, ayrı SSH tünelinin çalıştığını kanıtlamaz. Bu doğrulama sizin sorumluluğunuzdadır. Daha sonra `setup.sh` komutunu flagsiz çalıştırmak inbound SSH kuralını yeniden ekler.

## Tünel çalışmazsa

Sağlayıcı konsolunu kullanın. Sunucuda tünel container’ının durumunu, token dosyasını ve DNS hedeflerini kontrol edin. Kurtarma için web veya Grafana portlarını doğrudan internete açmayın.

## Bilerek yapılmayanlar

Özel WAF kuralları, Workers ve load balancer kurulmaz.
