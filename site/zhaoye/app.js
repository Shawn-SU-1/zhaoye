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
  stargazers_count: 5
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
  ".gallery-end",
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
const lightboxPrev = document.querySelector(".lightbox-prev");
const lightboxNext = document.querySelector(".lightbox-next");
const lightboxIndex = document.querySelector("#lightbox-index");
const lightboxName = document.querySelector("#lightbox-name");
const galleryGrid = document.querySelector(".gallery-grid");
const galleryButtons = [...document.querySelectorAll(".gallery-grid button[data-full]")];
const gallerySlides = galleryGrid ? [...galleryGrid.children] : [];
const galleryPrev = document.querySelector("#gallery-prev");
const galleryNext = document.querySelector("#gallery-next");
const galleryPlay = document.querySelector("#gallery-play");
const galleryPlayIcon = document.querySelector("#gallery-play-icon");
const galleryPlayLabel = document.querySelector("#gallery-play-label");
const galleryPosition = document.querySelector("#gallery-position");
const galleryProgress = document.querySelector("#gallery-progress");
const galleryLayout = document.querySelector(".gallery-layout");
let lightboxTimer = 0;
let lightboxTrigger = null;
let lightboxPosition = 0;
let galleryPositionIndex = 0;
let lightboxTouchX = null;
let galleryAutoplayEnabled = !reduceMotion;
let galleryAutoplayTimer = 0;
let galleryInView = false;
const GALLERY_AUTOPLAY_DELAY = 5_000;

function photoTitle(button) {
  const caption = button.closest("figure")?.querySelector("figcaption");
  return (caption?.textContent || "").replace(/^\s*\d+\s*/, "").trim();
}

function showLightboxPhoto(position) {
  if (!galleryButtons.length) return;
  lightboxPosition = (position + galleryButtons.length) % galleryButtons.length;
  const button = galleryButtons[lightboxPosition];
  const image = button.querySelector("img");
  lightboxImage.classList.add("is-changing");
  lightboxImage.src = button.dataset.full;
  lightboxImage.alt = image?.alt || "";
  lightboxIndex.textContent =
    `${String(lightboxPosition + 1).padStart(2, "0")} / ${String(galleryButtons.length).padStart(2, "0")}`;
  lightboxName.textContent = photoTitle(button);
  lightboxImage.onload = () => {
    requestAnimationFrame(() => lightboxImage.classList.remove("is-changing"));
  };
  const neighbor = galleryButtons[(lightboxPosition + 1) % galleryButtons.length];
  if (neighbor?.dataset.full) {
    const preload = new Image();
    preload.src = neighbor.dataset.full;
  }
}

function openLightbox(button) {
  window.clearTimeout(lightboxTimer);
  clearGalleryAutoplay();
  lightboxTrigger = button;
  lightboxPosition = Math.max(0, galleryButtons.indexOf(button));
  lightbox.hidden = false;
  document.body.style.overflow = "hidden";
  requestAnimationFrame(() => lightbox.classList.add("is-open"));
  showLightboxPhoto(lightboxPosition);
  lightboxClose.focus();
}

galleryButtons.forEach((button) => {
  button.addEventListener("click", () => openLightbox(button));
});

function closeLightbox() {
  window.clearTimeout(lightboxTimer);
  lightbox.classList.remove("is-open");
  document.body.style.overflow = "";
  lightboxTimer = window.setTimeout(() => {
    lightbox.hidden = true;
    lightboxImage.src = "";
    lightboxImage.classList.remove("is-changing");
    lightboxTrigger?.focus();
    scheduleGalleryAutoplay();
  }, reduceMotion ? 0 : 300);
}

lightboxClose?.addEventListener("click", closeLightbox);
lightboxPrev?.addEventListener("click", () => showLightboxPhoto(lightboxPosition - 1));
lightboxNext?.addEventListener("click", () => showLightboxPhoto(lightboxPosition + 1));
lightbox?.addEventListener("click", (event) => {
  if (event.target === lightbox) closeLightbox();
});
lightbox?.addEventListener("pointerdown", (event) => {
  lightboxTouchX = event.clientX;
});
lightbox?.addEventListener("pointerup", (event) => {
  if (lightboxTouchX == null) return;
  const distance = event.clientX - lightboxTouchX;
  lightboxTouchX = null;
  if (Math.abs(distance) < 48) return;
  showLightboxPhoto(lightboxPosition + (distance < 0 ? 1 : -1));
});
document.addEventListener("keydown", (event) => {
  if (lightbox.hidden) return;
  if (event.key === "Escape") closeLightbox();
  if (event.key === "ArrowLeft") showLightboxPhoto(lightboxPosition - 1);
  if (event.key === "ArrowRight") showLightboxPhoto(lightboxPosition + 1);
});

function updateGalleryPlayButton() {
  if (!galleryPlay) return;
  galleryPlay.setAttribute("aria-pressed", String(galleryAutoplayEnabled));
  galleryPlay.setAttribute(
    "aria-label",
    galleryAutoplayEnabled ? "暂停自动播放" : "开始自动播放",
  );
  galleryPlayIcon.textContent = galleryAutoplayEnabled ? "Ⅱ" : "▶";
  galleryPlayLabel.textContent = galleryAutoplayEnabled ? "暂停" : "播放";
}

function clearGalleryAutoplay() {
  window.clearTimeout(galleryAutoplayTimer);
  galleryAutoplayTimer = 0;
}

function scheduleGalleryAutoplay(delay = GALLERY_AUTOPLAY_DELAY) {
  clearGalleryAutoplay();
  if (!galleryAutoplayEnabled || !galleryInView || !lightbox?.hidden || document.hidden) return;
  galleryAutoplayTimer = window.setTimeout(() => {
    if (!galleryAutoplayEnabled || !galleryInView || !lightbox?.hidden || document.hidden) return;
    updateGalleryPosition(galleryPositionIndex + 1, "smooth");
    scheduleGalleryAutoplay();
  }, delay);
}

function setGalleryAutoplay(enabled, delay = GALLERY_AUTOPLAY_DELAY) {
  galleryAutoplayEnabled = enabled;
  updateGalleryPlayButton();
  if (enabled) scheduleGalleryAutoplay(delay);
  else clearGalleryAutoplay();
}

function updateGalleryPosition(index = galleryPositionIndex, behavior = "auto") {
  if (!galleryGrid || !gallerySlides.length) return;
  galleryPositionIndex =
    ((index % gallerySlides.length) + gallerySlides.length) % gallerySlides.length;
  const first = gallerySlides[0];
  const target = gallerySlides[galleryPositionIndex];
  if (behavior !== "none") {
    galleryGrid.scrollTo({
      left: Math.max(0, target.offsetLeft - first.offsetLeft),
      behavior,
    });
  }
  if (galleryPosition) {
    galleryPosition.textContent =
      `${String(galleryPositionIndex + 1).padStart(2, "0")} / ${String(gallerySlides.length).padStart(2, "0")}`;
  }
  if (galleryProgress) {
    galleryProgress.style.width = `${((galleryPositionIndex + 1) / gallerySlides.length) * 100}%`;
  }
  if (galleryPrev) galleryPrev.disabled = gallerySlides.length <= 1;
  if (galleryNext) galleryNext.disabled = gallerySlides.length <= 1;
}

galleryPrev?.addEventListener("click", () => {
  updateGalleryPosition(galleryPositionIndex - 1, "smooth");
  scheduleGalleryAutoplay();
});
galleryNext?.addEventListener("click", () => {
  updateGalleryPosition(galleryPositionIndex + 1, "smooth");
  scheduleGalleryAutoplay();
});
galleryPlay?.addEventListener("click", () => {
  setGalleryAutoplay(!galleryAutoplayEnabled);
});
galleryLayout?.addEventListener("pointerenter", clearGalleryAutoplay);
galleryLayout?.addEventListener("pointerleave", () => scheduleGalleryAutoplay());
galleryLayout?.addEventListener("focusin", clearGalleryAutoplay);
galleryLayout?.addEventListener("focusout", () => scheduleGalleryAutoplay(2_000));
galleryGrid?.addEventListener("pointerdown", clearGalleryAutoplay);
galleryGrid?.addEventListener("pointerup", () => scheduleGalleryAutoplay(2_500));
galleryGrid?.addEventListener("pointercancel", () => scheduleGalleryAutoplay(2_500));
galleryGrid?.addEventListener("scroll", () => {
  if (!gallerySlides.length) return;
  let nextIndex = 0;
  let nearest = Number.POSITIVE_INFINITY;
  gallerySlides.forEach((slide, index) => {
    const distance = Math.abs(
      slide.offsetLeft - gallerySlides[0].offsetLeft - galleryGrid.scrollLeft,
    );
    if (distance < nearest) {
      nearest = distance;
      nextIndex = index;
    }
  });
  if (nextIndex !== galleryPositionIndex) updateGalleryPosition(nextIndex, "none");
}, { passive: true });
window.addEventListener("resize", () => updateGalleryPosition(galleryPositionIndex, "none"));
document.addEventListener("visibilitychange", () => {
  if (document.hidden) clearGalleryAutoplay();
  else scheduleGalleryAutoplay();
});
if (galleryLayout && "IntersectionObserver" in window) {
  const galleryObserver = new IntersectionObserver((entries) => {
    galleryInView = entries.some((entry) => entry.isIntersecting);
    if (galleryInView) scheduleGalleryAutoplay();
    else clearGalleryAutoplay();
  }, { threshold: 0.32 });
  galleryObserver.observe(galleryLayout);
} else {
  galleryInView = true;
}
updateGalleryPosition(0, "none");
updateGalleryPlayButton();
if (galleryAutoplayEnabled) scheduleGalleryAutoplay();

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
  const githubStars = Number(data.github_stars);
  if (Number.isFinite(githubStars) && githubStars >= 0) {
    liveStars = true;
    cachedStars = githubStars;
    showStars(cachedStars, true);
  }
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
