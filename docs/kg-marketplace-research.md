# KG Marketplace Research — API Access & Client Building Feasibility

**Date:** 2026-06-02  
**Purpose:** Identify major KG marketplace platforms beyond Lalafo, test API access, and assess client building feasibility.

## Summary

| Platform | Type | API Access | Scraping Feasible | Priority |
|----------|------|------------|-------------------|----------|
| **mashina.kg** | Auto marketplace | ✅ Has API (Cloudflare protected) | ⚠️ Difficult | 🔴 High |
| **bazar.kg** | General classifieds | ❌ No public API | ⚠️ Possible (sitemap) | 🟡 Medium |
| **mymarket.kg** | General marketplace | ❌ No public API | ⚠️ Possible (HTML) | 🟡 Medium |
| **taider.kg** | Auto marketplace | ❌ No public API | ⚠️ Possible (sitemap) | 🟡 Medium |
| **autokyrgyz.com** | Auto marketplace | ❌ No public API | ⚠️ Possible (HTML) | 🟢 Low |
| **ogogo.kg** | Marketplace + installments | ❌ No public API | ⚠️ Possible (HTML) | 🟢 Low |
| **house.kg** | Real estate | ❌ No public API | ⚠️ Possible (HTML) | 🟢 Low |
| **skelar.kg** | General | ❌ No public API | ❌ Difficult | 🔴 Blocked |
| **sato.kg** | General | ❌ No public API | ❌ Difficult | 🔴 Blocked |
| **krrr.kg** | General | ❌ No public API | ❌ Difficult | 🔴 Blocked |

## Detailed Analysis

### 1. mashina.kg — ⭐ Highest Priority (Auto Marketplace)

**What it is:** The leading auto marketplace in Kyrgyzstan. Next.js 15 frontend, FastAPI backend.

**API Discovery:**
- Base URL: `https://api.mashina.kg/api`
- WebSocket: `wss://api.mashina.kg/api/chat/socket.io/`
- Endpoints found in JS bundles:
  - `/api/filters/passenger` — Car filters
  - `/api/filters/commercial` — Commercial vehicle filters
  - `/api/filters/moto` — Motorcycle filters
  - `/api/filters/parts` — Auto parts filters
  - `/api/filters/service` — Service filters
  - `/api/filters/urgent` — Urgent listings filters
  - `/api/filters/specs` — Specifications
  - `/api/catalog` — Main catalog endpoint (301 redirect)
- Sitemap reveals: `https://api.mashina.kg/ru/search/passenger`, `/commercial`, `/motorcycles`, `/special`, `/parts`, `/services`, `/urgent`

**Protection:** Cloudflare WAF (403 Forbidden on direct API access)

**Client Building Options:**
1. **Browser automation** (Playwright/Puppeteer) — Bypass Cloudflare, intercept API calls
2. **Mobile app reverse engineering** — APK likely has API calls without Cloudflare
3. **Official business account** — Contact for API partnership

**Verdict:** 🔴 **BLOCKED by Cloudflare** — Needs browser-based approach or partnership

---

### 2. bazar.kg — 🟡 Medium Priority (General Classifieds)

**What it is:** General classifieds platform (electronics, home, fashion, etc.). NOT auto-focused.

**API Discovery:**
- No public API found
- Sitemap available: `sitemap.categories.xml`, `sitemap.ads.xml`, `sitemap.shops.xml`
- CDN: `cdn.bazar.kg` for images
- Multi-language support (ru, en, kg)

**Scraping Feasibility:**
- Sitemap provides listing URLs
- Individual listing pages likely have structured data
- No Cloudflare protection detected

**Client Building Options:**
1. **Sitemap-based scraper** — Parse sitemap for listing URLs, fetch individual pages
2. **HTML parsing** — Extract data from listing pages

**Verdict:** 🟡 **FEASIBLE** — Sitemap-based approach, but limited to general goods (not auto)

---

### 3. mymarket.kg — 🟡 Medium Priority (General Marketplace)

**What it is:** General marketplace with categories: transport, real estate, electronics, etc.

**API Discovery:**
- No public API found
- Search URLs: `mymarket.kg/search/{category}/{subcategory}/{item}.html`
- Uses jQuery for frontend
- No Cloudflare protection detected

**Scraping Feasibility:**
- Search URLs are predictable
- Individual listing pages likely have structured data
- No Cloudflare protection detected

**Client Building Options:**
1. **Search-based scraper** — Construct search URLs, parse results
2. **HTML parsing** — Extract data from listing pages

**Verdict:** 🟡 **FEASIBLE** — Search-based approach, includes transport category

---

### 4. taider.kg — 🟡 Medium Priority (Auto Marketplace)

**What it is:** Auto marketplace similar to mashina.kg

**API Discovery:**
- No public API found
- Sitemap available with listing URLs
- Search: `taider.kg/search?q={search_term}`
- No Cloudflare protection detected

**Scraping Feasibility:**
- Sitemap provides listing URLs
- Individual listing pages likely have structured data
- No Cloudflare protection detected

**Client Building Options:**
1. **Sitemap-based scraper** — Parse sitemap for listing URLs
2. **Search-based scraper** — Use search endpoint

**Verdict:** 🟡 **FEASIBLE** — Good alternative to mashina.kg for auto

---

### 5. Other Platforms (Low Priority)

**autokyrgyz.com, ogogo.kg, house.kg, skelar.kg, sato.kg, krrr.kg:**
- No public APIs found
- Some have sitemaps
- Limited scraping feasibility
- Lower priority for integration

---

## Recommendations

### Immediate Actions (Next 2 Weeks)

1. **mashina.kg** — Contact for business/API partnership
   - They have a "Business Account" program
   - Email likely: business@mashina.kg or similar
   - Offer data partnership in exchange for API access

2. **taider.kg** — Build scraper as backup
   - No Cloudflare protection
   - Sitemap-based approach
   - Good auto marketplace alternative

3. **bazar.kg** — Build scraper for general goods
   - Sitemap-based approach
   - Useful for electronics, home goods, etc.

### Medium Term (1-2 Months)

1. **mashina.kg** — Browser automation if partnership fails
   - Use Playwright/Puppeteer to bypass Cloudflare
   - Intercept API calls during browser session
   - Cache results to reduce requests

2. **mymarket.kg** — Add to scraper rotation
   - Search-based approach
   - Includes transport category

### Technical Approach

**For platforms without APIs (bazar.kg, mymarket.kg, taider.kg):**

```clojure
;; Proposed architecture
(defn scrape-platform [platform config]
  (let [sitemap (fetch-sitemap (:sitemap-url config))
        listings (parse-sitemap sitemap)
        details (map #(fetch-listing-detail % config) listings)]
    (normalize-data details config)))

;; Configuration
(def platforms
  {:bazar {:sitemap-url "https://www.bazar.kg/sitemap.ads.xml"
           :detail-url-pattern "https://www.bazar.kg/details/{slug}"
           :selectors {:title "h1" :price ".price" :description ".description"}}
   :taider {:sitemap-url "https://taider.kg/sitemap.xml"
            :detail-url-pattern "https://taider.kg/{slug}"
            :selectors {:title "h1" :price ".price" :description ".description"}}})
```

**For mashina.kg (Cloudflare protected):**

```clojure
;; Option 1: Browser automation
(defn scrape-mashina [query]
  (let [browser (launch-browser)
        page (new-page browser)]
    (goto page "https://www.mashina.kg/")
    (fill page "#search" query)
    (click page "#search-button")
    (wait-for-selector page ".listing-card")
    (let [listings (query-all page ".listing-card")]
      (map #(extract-listing %) listings))))

;; Option 2: Mobile app reverse engineering
;; - Decompile APK
;; - Find API endpoints
;; - Replicate requests without Cloudflare
```

## Data Normalization

All scraped data should be normalized to a common schema:

```clojure
{:id "unique-id"
 :platform "mashina|bazar|taider|mymarket"
 :title "iPhone 13 Pro"
 :price 45000
 :currency "KGS"
 :category "electronics|auto|real-estate|..."
 :url "https://..."
 :images ["https://..."]
 :location "Bishkek"
 :seller {:name "..." :phone "..."}
 :created-at #inst "2026-06-01"
 :updated-at #inst "2026-06-02"
 :raw-data {...}}
```

## Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| Cloudflare blocks scrapers | 🔴 High | Use residential proxies, rotate user agents |
| Legal issues (ToS violation) | 🟡 Medium | Check ToS, use official APIs when available |
| Rate limiting | 🟡 Medium | Implement delays, respect robots.txt |
| Site structure changes | 🟢 Low | Use flexible selectors, monitor changes |

## Next Steps

1. [ ] Contact mashina.kg for API partnership
2. [ ] Build taider.kg scraper (no Cloudflare)
3. [ ] Build bazar.kg scraper (sitemap-based)
4. [ ] Test mymarket.kg search-based scraping
5. [ ] Normalize data schema across platforms
6. [ ] Implement caching/deduplication

---

**Bottom Line:** mashina.kg is the prize but blocked by Cloudflare. Start with taider.kg and bazar.kg (feasible), while pursuing mashina.kg partnership. mymarket.kg adds general goods coverage.
