const guideShots = [...document.querySelectorAll(".guide-screen[data-full]")];
const guideLightbox = document.querySelector("#photo-lightbox");
const guideLightboxImage = document.querySelector("#lightbox-image");
const guideLightboxClose = document.querySelector(".lightbox-close");
const guideLightboxPrev = document.querySelector(".lightbox-prev");
const guideLightboxNext = document.querySelector(".lightbox-next");
const guideLightboxIndex = document.querySelector("#lightbox-index");
const guideLightboxName = document.querySelector("#lightbox-name");
const guideNav = document.querySelector(".nav");
let guideShotIndex = 0;
let guideTrigger = null;
let guideCloseTimer = 0;
let guideTouchX = null;

function guideShotTitle(button) {
  return button.querySelector(":scope > span:not(.guide-screen-media) b")?.textContent
    ? button.querySelector(":scope > span:not(.guide-screen-media)").textContent.replace(/^\s*\d+\s*/, "").trim()
    : button.getAttribute("aria-label") || "照夜使用说明";
}

function showGuideShot(position) {
  if (!guideShots.length) return;
  guideShotIndex = (position + guideShots.length) % guideShots.length;
  const button = guideShots[guideShotIndex];
  const image = button.querySelector("img");
  guideLightboxImage.classList.add("is-changing");
  guideLightboxImage.src = button.dataset.full;
  guideLightboxImage.alt = image?.alt || "";
  guideLightboxIndex.textContent =
    `${String(guideShotIndex + 1).padStart(2, "0")} / ${String(guideShots.length).padStart(2, "0")}`;
  guideLightboxName.textContent = guideShotTitle(button);
  guideLightboxImage.onload = () => {
    requestAnimationFrame(() => guideLightboxImage.classList.remove("is-changing"));
  };
}

function openGuideShot(button) {
  window.clearTimeout(guideCloseTimer);
  guideTrigger = button;
  guideShotIndex = Math.max(0, guideShots.indexOf(button));
  guideLightbox.hidden = false;
  document.body.style.overflow = "hidden";
  requestAnimationFrame(() => guideLightbox.classList.add("is-open"));
  showGuideShot(guideShotIndex);
  guideLightboxClose.focus();
}

function closeGuideShot() {
  guideLightbox.classList.remove("is-open");
  document.body.style.overflow = "";
  guideCloseTimer = window.setTimeout(() => {
    guideLightbox.hidden = true;
    guideLightboxImage.src = "";
    guideTrigger?.focus();
  }, 280);
}

guideShots.forEach((button) => {
  button.addEventListener("click", () => openGuideShot(button));
});

guideLightboxClose?.addEventListener("click", closeGuideShot);
guideLightboxPrev?.addEventListener("click", () => showGuideShot(guideShotIndex - 1));
guideLightboxNext?.addEventListener("click", () => showGuideShot(guideShotIndex + 1));
guideLightbox?.addEventListener("click", (event) => {
  if (event.target === guideLightbox) closeGuideShot();
});
guideLightbox?.addEventListener("pointerdown", (event) => {
  guideTouchX = event.clientX;
});
guideLightbox?.addEventListener("pointerup", (event) => {
  if (guideTouchX == null) return;
  const distance = event.clientX - guideTouchX;
  guideTouchX = null;
  if (Math.abs(distance) < 48) return;
  showGuideShot(guideShotIndex + (distance < 0 ? 1 : -1));
});
document.addEventListener("keydown", (event) => {
  if (guideLightbox.hidden) return;
  if (event.key === "Escape") closeGuideShot();
  if (event.key === "ArrowLeft") showGuideShot(guideShotIndex - 1);
  if (event.key === "ArrowRight") showGuideShot(guideShotIndex + 1);
});

const guideTocLinks = [...document.querySelectorAll(".guide-toc a[href^='#']")];
let guideScrollFrame = 0;

function updateGuideNav() {
  guideScrollFrame = 0;
  guideNav?.classList.toggle("is-scrolled", window.scrollY > 12);
  const scrollable = document.documentElement.scrollHeight - window.innerHeight;
  const progress = scrollable > 0 ? Math.min(1, Math.max(0, window.scrollY / scrollable)) : 0;
  document.documentElement.style.setProperty("--guide-progress", progress.toFixed(4));
}

function requestGuideNavUpdate() {
  if (guideScrollFrame) return;
  guideScrollFrame = requestAnimationFrame(updateGuideNav);
}

window.addEventListener("scroll", requestGuideNavUpdate, { passive: true });
window.addEventListener("resize", requestGuideNavUpdate);
updateGuideNav();

if (guideTocLinks.length && "IntersectionObserver" in window) {
  const guideSections = guideTocLinks
    .map((link) => document.querySelector(link.getAttribute("href")))
    .filter(Boolean);
  const tocObserver = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      for (const link of guideTocLinks) {
        link.classList.toggle("is-active", link.getAttribute("href") === `#${entry.target.id}`);
      }
    }
  }, { rootMargin: "-22% 0px -62% 0px", threshold: 0 });
  guideSections.forEach((section) => tocObserver.observe(section));
}
