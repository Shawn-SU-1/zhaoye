const fallback = {
  version: "0.2.5",
  sha256: "fd2bf48e199d2c41976b82438fbf387da03c875010f6250c6c51c6c599996258",
  github_release_url: "https://github.com/Shawn-SU-1/zhaoye/releases/tag/v0.2.5",
  github_apk_url: "https://github.com/Shawn-SU-1/zhaoye/releases/download/v0.2.5/Zhaoye-0.2.5-arm64-v8a.apk",
  domestic_url: "https://wwbjy.lanzouv.com/b0hebl34b",
  domestic_password: "23nt",
  baidu_url: "https://pan.baidu.com/s/1f_aZvZQsR4no8KrLcxiJHQ?pwd=h1ec",
  baidu_password: "h1ec",
  website_url: "https://zhaoye.8889.hair",
  stargazers_count: 3
};

const versionBadge = document.querySelector("#version-badge");
const lanzouLink = document.querySelector("#lanzou-link");
const baiduLink = document.querySelector("#baidu-link");
const githubRelease = document.querySelector("#github-release");
const hashValue = document.querySelector("#sha256");
const copyHash = document.querySelector("#copy-hash");
const track = document.querySelector("#changelog-track");
const previous = document.querySelector("#changes-prev");
const next = document.querySelector("#changes-next");
const position = document.querySelector("#changes-position");
const starCount = document.querySelector("#star-count");
let cachedStars = fallback.stargazers_count;
let liveStars = false;
const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

const siteNav = document.querySelector(".nav");
const navLinks = [...document.querySelectorAll(".nav nav a[href^='#']")];
const trackedSections = [...document.querySelectorAll("main section[id]")];
let scrollFrame = 0;

function updatePageChrome() {
  scrollFrame = 0;
  siteNav?.classList.toggle("is-scrolled", window.scrollY > 18);
  const marker = window.scrollY + window.innerHeight * 0.34;
  let active = "";
  for (const section of trackedSections) {
    if (section.offsetTop <= marker) active = section.id;
  }
  navLinks.forEach((link) => {
    const selected = link.getAttribute("href") === `#${active}`;
    link.classList.toggle("is-active", selected);
    if (selected) link.setAttribute("aria-current", "location");
    else link.removeAttribute("aria-current");
  });
}

function requestPageChrome() {
  if (scrollFrame) return;
  scrollFrame = window.requestAnimationFrame(updatePageChrome);
}

window.addEventListener("scroll", requestPageChrome, { passive: true });
window.addEventListener("resize", requestPageChrome);
updatePageChrome();

function observeReveal(nodes) {
  const items = [...nodes].filter((node) => node && !node.classList.contains("reveal"));
  if (!items.length) return;
  if (reduceMotion || !("IntersectionObserver" in window)) {
    items.forEach((node) => node.classList.add("reveal", "is-visible"));
    return;
  }
  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      entry.target.classList.add("is-visible");
      observer.unobserve(entry.target);
    }
  }, { threshold: 0.12, rootMargin: "0px 0px -7% 0px" });
  items.forEach((node, index) => {
    node.classList.add("reveal");
    node.style.setProperty("--reveal-delay", `${Math.min(index % 5, 4) * 55}ms`);
    observer.observe(node);
  });
}

observeReveal(document.querySelectorAll([
  ".section-head",
  ".gallery-grid figure",
  ".visitor-stats article",
  ".visitor-map-card",
  ".download-card",
  ".checksum",
  ".changes-head",
  ".changelog-viewport",
  ".features article",
  ".about > *"
].join(",")));

function showStars(count, live) {
  const value = Number.isFinite(Number(count)) ? Number(count) : 0;
  starCount.textContent = `${new Intl.NumberFormat("zh-CN").format(value)} Stars · ${live ? "点 Star" : "登录后点 Star"}`;
}

fetch("update.json", { cache: "no-store" })
  .then((response) => response.ok ? response.json() : Promise.reject())
  .then((data) => {
    const version = data.version || fallback.version;
    versionBadge.textContent = `v${version}`;
    lanzouLink.href = data.domestic_url || fallback.domestic_url;
    baiduLink.href = data.baidu_url || fallback.baidu_url;
    githubRelease.href = data.github_apk_url || data.github_release_url || fallback.github_apk_url;
    hashValue.textContent = data.sha256 || fallback.sha256;
    cachedStars = Number(data.stargazers_count ?? cachedStars);
    if (!liveStars) showStars(cachedStars, false);
  })
  .catch(() => {
    versionBadge.textContent = `v${fallback.version}`;
  });

copyHash?.addEventListener("click", async () => {
  const value = hashValue.textContent.trim();
  try {
    await navigator.clipboard.writeText(value);
    copyHash.textContent = "已复制";
    setTimeout(() => copyHash.textContent = "复制校验值", 1400);
  } catch {
    window.prompt("复制 SHA-256", value);
  }
});

function versionParts(value) {
  return String(value).match(/\d+/g)?.map(Number) || [];
}

function isNewer(left, right) {
  const a = versionParts(left);
  const b = versionParts(right);
  for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
    const delta = (a[index] || 0) - (b[index] || 0);
    if (delta !== 0) return delta > 0;
  }
  return false;
}

function releaseCard(release, index) {
  const card = document.createElement("article");
  card.className = `release-card${index === 0 ? " latest" : ""}`;

  const topline = document.createElement("div");
  topline.className = "release-topline";
  const date = document.createElement("span");
  date.textContent = release.date;
  topline.appendChild(date);
  if (index === 0) {
    const badge = document.createElement("span");
    badge.className = "latest-badge";
    badge.textContent = "最新版本";
    topline.appendChild(badge);
  }

  const heading = document.createElement("h3");
  heading.textContent = `v${release.version} · ${release.title}`;
  const summary = document.createElement("p");
  summary.className = "release-summary";
  summary.textContent = release.summary || "";
  card.append(topline, heading, summary);

  for (const group of release.groups || []) {
    const block = document.createElement("section");
    block.className = "release-group";
    const title = document.createElement("h4");
    title.textContent = group.title;
    const list = document.createElement("ul");
    for (const item of group.items || []) {
      const entry = document.createElement("li");
      entry.textContent = item;
      list.appendChild(entry);
    }
    block.append(title, list);
    card.appendChild(block);
  }
  return card;
}

let cards = [];
let currentIndex = 0;

function stepWidth() {
  if (!cards.length) return 0;
  return cards[0].getBoundingClientRect().width + 18;
}

function setIndex(index, behavior = "smooth") {
  currentIndex = Math.max(0, Math.min(index, cards.length - 1));
  track.scrollTo({ left: currentIndex * stepWidth(), behavior });
  updateControls();
}

function updateControls() {
  position.textContent = `${currentIndex + 1} / ${cards.length}`;
  previous.disabled = currentIndex === 0;
  next.disabled = currentIndex === cards.length - 1;
  const origin = track.getBoundingClientRect().left;
  cards.forEach((card, index) => {
    const offset = Math.max(0, (card.getBoundingClientRect().left - origin) / stepWidth());
    card.style.opacity = String(Math.max(0.22, 1 - offset * 0.2));
    card.style.filter = `blur(${Math.min(3.6, offset * 0.9).toFixed(2)}px)`;
    card.style.transform = `scale(${Math.max(0.94, 1 - offset * 0.025)})`;
    card.classList.toggle("is-current", index === currentIndex);
  });
}

fetch("changelog.json", { cache: "no-store" })
  .then((response) => response.ok ? response.json() : Promise.reject())
  .then((releases) => {
    const ordered = [...releases].sort((a, b) => isNewer(a.version, b.version) ? -1 : 1);
    track.replaceChildren(...ordered.map(releaseCard));
    cards = [...track.querySelectorAll(".release-card")];
    updateControls();
  })
  .catch(() => {
    track.innerHTML = '<article class="release-card release-loading">更新记录暂时不可用</article>';
  });

previous?.addEventListener("click", () => setIndex(currentIndex - 1));
next?.addEventListener("click", () => setIndex(currentIndex + 1));
track?.addEventListener("scroll", () => {
  const step = stepWidth();
  if (!step) return;
  const origin = track.getBoundingClientRect().left;
  let nextIndex = 0;
  let nearest = Number.POSITIVE_INFINITY;
  cards.forEach((card, index) => {
    const distance = Math.abs(card.getBoundingClientRect().left - origin);
    if (distance < nearest) {
      nearest = distance;
      nextIndex = index;
    }
  });
  if (nextIndex !== currentIndex) {
    currentIndex = nextIndex;
  }
  updateControls();
}, { passive: true });
track?.addEventListener("keydown", (event) => {
  if (event.key === "ArrowLeft") {
    event.preventDefault();
    setIndex(currentIndex - 1);
  } else if (event.key === "ArrowRight") {
    event.preventDefault();
    setIndex(currentIndex + 1);
  }
});
window.addEventListener("resize", () => setIndex(currentIndex, "auto"));

fetch("https://api.github.com/repos/Shawn-SU-1/zhaoye", {
  headers: { Accept: "application/vnd.github+json" }
})
  .then((response) => response.ok ? response.json() : Promise.reject())
  .then((repo) => {
    liveStars = true;
    cachedStars = repo.stargazers_count;
    showStars(cachedStars, true);
  })
  .catch(() => {
    showStars(cachedStars, false);
  });

const lightbox = document.querySelector("#photo-lightbox");
const lightboxImage = document.querySelector("#lightbox-image");
const lightboxClose = document.querySelector(".lightbox-close");
let lightboxTimer = 0;
let lightboxTrigger = null;

function openLightbox(button) {
  const image = button.querySelector("img");
  window.clearTimeout(lightboxTimer);
  lightboxTrigger = button;
  lightboxImage.src = button.dataset.full;
  lightboxImage.alt = image?.alt || "";
  lightbox.hidden = false;
  document.body.style.overflow = "hidden";
  requestAnimationFrame(() => lightbox.classList.add("is-open"));
  lightboxClose.focus();
}

document.querySelectorAll(".gallery-grid button[data-full]").forEach((button) => {
  button.addEventListener("click", () => openLightbox(button));
});

function closeLightbox() {
  window.clearTimeout(lightboxTimer);
  lightbox.classList.remove("is-open");
  document.body.style.overflow = "";
  lightboxTimer = window.setTimeout(() => {
    lightbox.hidden = true;
    lightboxImage.src = "";
    lightboxTrigger?.focus();
  }, reduceMotion ? 0 : 300);
}

lightboxClose?.addEventListener("click", closeLightbox);
lightbox?.addEventListener("click", (event) => {
  if (event.target === lightbox) closeLightbox();
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && !lightbox.hidden) closeLightbox();
});

const statViews = document.querySelector("#stat-views");
const statVisitors = document.querySelector("#stat-visitors");
const statCities = document.querySelector("#stat-cities");
const statUpdated = document.querySelector("#stat-updated");
const visitorMapPoints = document.querySelector("#visitor-map-points");
const visitorMapEmpty = document.querySelector("#visitor-map-empty");
const visitorPlaces = document.querySelector("#visitor-places");
const consentBanner = document.querySelector("#cookie-consent");
const consentAccept = document.querySelector("#consent-accept");
const consentEssential = document.querySelector("#consent-essential");
const consentSettings = document.querySelector("#consent-settings");
const numberFormat = new Intl.NumberFormat("zh-CN");
let visitReported = false;
let consentTimer = 0;

function readConsent() {
  const match = document.cookie.match(/(?:^|;\s*)zhaoye_consent=(accepted|essential)(?:;|$)/);
  if (match) return match[1];
  try {
    const stored = localStorage.getItem("zhaoye_consent");
    return stored === "accepted" || stored === "essential" ? stored : "";
  } catch {
    return "";
  }
}

function setConsent(value) {
  const secure = location.protocol === "https:" ? "; Secure" : "";
  document.cookie = `zhaoye_consent=${value}; Max-Age=15552000; Path=/; SameSite=Lax${secure}`;
  try {
    localStorage.setItem("zhaoye_consent", value);
  } catch {
    // The cookie remains the source of truth if storage is unavailable.
  }
  hideConsent();
}

function showConsent() {
  if (!consentBanner) return;
  window.clearTimeout(consentTimer);
  consentBanner.hidden = false;
  requestAnimationFrame(() => consentBanner.classList.add("is-visible"));
}

function hideConsent(immediate = false) {
  if (!consentBanner) return;
  window.clearTimeout(consentTimer);
  consentBanner.classList.remove("is-visible");
  consentTimer = window.setTimeout(() => {
    consentBanner.hidden = true;
  }, immediate || reduceMotion ? 0 : 280);
}

function recentLabel(value) {
  if (!value) return "—";
  const date = new Date(`${value.replace(" ", "T")}Z`);
  if (Number.isNaN(date.getTime())) return "—";
  return `${date.getMonth() + 1}月${date.getDate()}日`;
}

function canonicalCountry(place) {
  const code = String(place.country_code || "").trim().toUpperCase();
  const country = String(place.country || "").trim();
  if (code === "TW" || ["台湾", "台灣", "Taiwan", "Taiwan, China"].includes(country)) {
    return "中国台湾";
  }
  if (code === "HK" || ["香港", "Hong Kong", "Hong Kong, China"].includes(country)) {
    return "中国香港";
  }
  if (code === "MO" || ["澳门", "澳門", "Macao", "Macau", "Macao, China"].includes(country)) {
    return "中国澳门";
  }
  return country;
}

function mergeLocations(rawLocations) {
  const merged = new Map();
  for (const place of rawLocations) {
    const location = String(place.location || "").trim();
    if (!location) continue;
    const country = canonicalCountry(place);
    const key = `${location}\u0000${country}`;
    const visits = Number(place.visits || 0);
    const visitors = Number(place.visitors || 0);
    const lat = Number(place.lat);
    const lon = Number(place.lon);
    const current = merged.get(key);
    if (!current) {
      merged.set(key, {
        ...place,
        location,
        country,
        country_code: String(place.country_code || "").toUpperCase(),
        visits,
        visitors,
        lat,
        lon,
      });
      continue;
    }
    current.visits += visits;
    current.visitors += visitors;
    if (Number.isFinite(lat) && Number.isFinite(lon)) {
      current.lat = (current.lat + lat) / 2;
      current.lon = (current.lon + lon) / 2;
    }
  }
  return [...merged.values()].sort((a, b) =>
    b.visits - a.visits || b.visitors - a.visitors);
}

function renderStats(data) {
  statViews.textContent = numberFormat.format(data.views || 0);
  statVisitors.textContent = numberFormat.format(data.visitors || 0);
  statCities.textContent = numberFormat.format(data.cities || 0);
  statUpdated.textContent = recentLabel(data.updated_at);

  const locations = mergeLocations(Array.isArray(data.locations) ? data.locations : []);
  const maximum = Math.max(1, ...locations.map((place) => place.visits || 0));
  visitorMapPoints.replaceChildren();
  visitorPlaces.replaceChildren();
  visitorMapEmpty.hidden = locations.length > 0;

  for (const [index, place] of locations.entries()) {
    if (!Number.isFinite(place.lon) || !Number.isFinite(place.lat)) continue;
    const marker = document.createElement("button");
    marker.type = "button";
    marker.className = "map-point";
    marker.style.left = `${((place.lon + 180) / 360) * 100}%`;
    marker.style.top = `${((90 - place.lat) / 180) * 100}%`;
    marker.style.setProperty("--size", `${Math.round(10 + Math.sqrt(place.visits / maximum) * 20)}px`);
    marker.style.setProperty("--marker-delay", `${Math.min(index, 12) * 45}ms`);
    const placeName = place.country && place.country !== place.location
      ? `${place.location} ${place.country}`
      : place.location;
    marker.title = `${placeName} · ${place.visitors} 位访客 · ${place.visits} 次访问`;
    marker.setAttribute("aria-label", marker.title);
    visitorMapPoints.appendChild(marker);

    const tag = document.createElement("span");
    const name = document.createElement("span");
    const count = document.createElement("b");
    name.textContent = placeName;
    count.textContent = `${place.visits} 次`;
    tag.style.animationDelay = `${Math.min(index, 12) * 40}ms`;
    tag.append(name, count);
    visitorPlaces.appendChild(tag);
  }

  if (!visitorPlaces.children.length) {
    const empty = document.createElement("span");
    empty.className = "place-empty";
    empty.textContent = "等待第一位访客";
    visitorPlaces.appendChild(empty);
  }
}

async function refreshStats() {
  try {
    const response = await fetch("/api/zhaoye/stats", {
      cache: "no-store",
      credentials: "same-origin",
    });
    if (!response.ok) return;
    renderStats(await response.json());
  } catch {
    // Analytics is non-essential and must never interrupt the page.
  }
}

async function reportVisit() {
  if (visitReported || readConsent() !== "accepted") return;
  visitReported = true;
  try {
    const response = await fetch("/api/zhaoye/visit", {
      method: "POST",
      cache: "no-store",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
    if (response.ok) {
      const data = await response.json();
      if (data.ok) renderStats(data);
    }
  } catch {
    // Counting a visit is optional; the static site remains fully functional.
  }
}

const initialConsent = readConsent();
if (initialConsent) {
  hideConsent(true);
  if (initialConsent === "accepted") reportVisit();
} else {
  consentTimer = window.setTimeout(showConsent, reduceMotion ? 0 : 900);
}

consentAccept?.addEventListener("click", () => {
  setConsent("accepted");
  reportVisit();
});
consentEssential?.addEventListener("click", () => {
  setConsent("essential");
});
consentSettings?.addEventListener("click", () => {
  showConsent();
  consentBanner.scrollIntoView({ block: "end" });
});

refreshStats();
window.setInterval(refreshStats, 60_000);
