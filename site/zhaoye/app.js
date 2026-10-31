const fallback = {
  version: "0.2.2",
  sha256: "62a1893355fca82df05dedeb42857424b8c65574712974e6add1003cf16f144c",
  github_release_url: "https://github.com/Shawn-SU-1/zhaoye/releases/latest",
  github_apk_url: "https://github.com/Shawn-SU-1/zhaoye/releases/latest",
  domestic_url: "https://wwbjy.lanzouv.com/b0hebl34b",
  domestic_password: "23nt",
  baidu_url: "https://pan.baidu.com/s/1f_aZvZQsR4no8KrLcxiJHQ?pwd=h1ec",
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
  cards.forEach((card) => {
    const offset = Math.max(0, (card.getBoundingClientRect().left - origin) / stepWidth());
    card.style.opacity = String(Math.max(0.22, 1 - offset * 0.2));
    card.style.filter = `blur(${Math.min(3.6, offset * 0.9).toFixed(2)}px)`;
    card.style.transform = `scale(${Math.max(0.94, 1 - offset * 0.025)})`;
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

document.querySelectorAll(".gallery-grid button[data-full]").forEach((button) => {
  button.addEventListener("click", () => {
    const image = button.querySelector("img");
    lightboxImage.src = button.dataset.full;
    lightboxImage.alt = image?.alt || "";
    lightbox.hidden = false;
    document.body.style.overflow = "hidden";
    lightboxClose.focus();
  });
});

function closeLightbox() {
  lightbox.hidden = true;
  lightboxImage.src = "";
  document.body.style.overflow = "";
}

lightboxClose?.addEventListener("click", closeLightbox);
lightbox?.addEventListener("click", (event) => {
  if (event.target === lightbox) closeLightbox();
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && !lightbox.hidden) closeLightbox();
});
