const fallback = {
  version: "0.2.2",
  release_notes: "蓝奏云改为固定文件夹分享；下载链接保持不变。",
  github_release_url: "https://github.com/Shawn-SU-1/zhaoye/releases/latest",
  github_apk_url: "https://github.com/Shawn-SU-1/zhaoye/releases/latest",
  domestic_url: "https://wwbjy.lanzouv.com/b0hebl34b",
  baidu_url: "https://pan.baidu.com/s/1f_aZvZQsR4no8KrLcxiJHQ?pwd=h1ec"
};

const versionBadge = document.querySelector("#version-badge");
const changeVersion = document.querySelector("#change-version");
const lanzouLink = document.querySelector("#lanzou-link");
const baiduLink = document.querySelector("#baidu-link");
const githubRelease = document.querySelector("#github-release");
const releaseNotes = document.querySelector("#release-notes");
const copyHash = document.querySelector("#copy-hash");

fetch("update.json", { cache: "no-store" })
  .then((response) => response.ok ? response.json() : Promise.reject())
  .then((data) => {
    const version = data.version || fallback.version;
    versionBadge.textContent = `v${version}`;
    changeVersion.textContent = version;
    lanzouLink.href = data.domestic_url || fallback.domestic_url;
    baiduLink.href = data.baidu_url || fallback.baidu_url;
    githubRelease.href = data.github_apk_url || data.github_release_url || fallback.github_apk_url;
    if (data.release_notes) {
      releaseNotes.innerHTML = "";
      data.release_notes
        .split(/\n+/)
        .map((line) => line.replace(/^\s*[-•]\s*/, "").trim())
        .filter(Boolean)
        .forEach((line) => {
          const item = document.createElement("li");
          item.textContent = line;
          releaseNotes.appendChild(item);
        });
    }
  })
  .catch(() => {
    versionBadge.textContent = `v${fallback.version}`;
    changeVersion.textContent = fallback.version;
  });

copyHash?.addEventListener("click", async () => {
  const value = document.querySelector("#sha256").textContent.trim();
  try {
    await navigator.clipboard.writeText(value);
    copyHash.textContent = "已复制";
    setTimeout(() => copyHash.textContent = "复制校验值", 1400);
  } catch {
    window.prompt("复制 SHA-256", value);
  }
});
