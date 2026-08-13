-- ============================================================================
-- wal-meow test script
--
-- Amaç:
--   1) UTF-8 düzeltmesini test etmek (Türkçe karakterli tablo/kolon adı VE
--      Türkçe karakterli veri, hem INSERT hem UPDATE üzerinden).
--   2) INSERT / UPDATE / DELETE event akışının doğru çalıştığını görmek.
--
-- Nasıl kullanılır:
--   1) Önce uygulamayı çalıştır (IntelliJ'den Main.main() veya
--      `./gradlew run`) — konsolda "WAL okunuyor..." log'unu gör.
--   2) Bu scripti çalıştırırken uygulamanın konsolunu izle:
--        docker exec -i postgres_db psql -U db -d db -f - < test_wal.sql
--      (ya da içeriği herhangi bir SQL istemcisinde localhost:5432/db'ye
--      bağlanıp elle çalıştır)
--   3) Her adımdan sonra uygulama konsolunda BEGIN/INSERT/UPDATE/DELETE/COMMIT
--      satırları görünmeli ve Türkçe karakterler (ş, ğ, ı, ö, ç, ü, İ) BOZULMADAN
--      basılmalı — daha önce bu karakterler "?" veya "Ã..." gibi mojibake
--      olarak görünüyordu, artık düzgün çıkmalı.
--
-- Busy-wait (CPU) düzeltmesini test etmek için:
--   Uygulamayı başlattıktan sonra, bu scripti çalıştırmadan ÖNCE ~30 saniye
--   bekleyip Görev Yöneticisi'nden java.exe process'inin CPU kullanımına bak.
--   Düzeltmeden önce bu süre boyunca bir çekirdek sürekli %100 civarında
--   takılı kalıyordu; artık boşta neredeyse %0 olmalı.
-- ============================================================================

DROP TABLE IF EXISTS "başvurular";

CREATE TABLE "başvurular" (
    id            serial PRIMARY KEY,
    "başvuran_adı" text NOT NULL,
    "açıklama"     text,
    "durum"        text NOT NULL DEFAULT 'beklemede',
    olusturma_tarihi timestamptz NOT NULL DEFAULT now()
);

-- 1) INSERT — Türkçe karakterli veri
INSERT INTO "başvurular" ("başvuran_adı", "açıklama", "durum") VALUES
    ('Şükrü Öztürk',   'Çöp konteynerı kırık, acil değişim gerekiyor.', 'beklemede'),
    ('Gülşah Çelik',   'Kaldırımdaki çukur güvenlik riski oluşturuyor.', 'beklemede'),
    ('İbrahim Yıldız', 'Sokak lambası yanmıyor, aydınlatma yetersiz.',  'beklemede');

-- 2) UPDATE — durumu değiştir (eski/yeni değer karşılaştırması için)
UPDATE "başvurular"
SET "durum" = 'inceleniyor',
    "açıklama" = "açıklama" || ' [Güncelleme: ekip yönlendirildi.]'
WHERE "başvuran_adı" = 'Şükrü Öztürk';

-- 3) Bir tanesini tamamlandı olarak işaretle
UPDATE "başvurular"
SET "durum" = 'tamamlandı'
WHERE "başvuran_adı" = 'Gülşah Çelik';

-- 4) DELETE — bir kaydı sil
DELETE FROM "başvurular"
WHERE "başvuran_adı" = 'İbrahim Yıldız';

-- Son durumu kontrol et
SELECT * FROM "başvurular";
