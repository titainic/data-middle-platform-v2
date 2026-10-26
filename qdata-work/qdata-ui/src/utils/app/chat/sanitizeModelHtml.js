import createDOMPurify from "dompurify";

// 独立实例的策略仅用于模型输出，不改变其他组件的消毒配置。
const sanitizer = createDOMPurify(window);

sanitizer.addHook("uponSanitizeAttribute", (node, attribute) => {
  if (attribute.attrName === "data-copy") {
    // DOMPurify 默认 trim 属性值；代码复制须保留原始缩进和末尾换行。
    attribute.attrValue = node.getAttribute("data-copy");
  }
  if (node.nodeName !== "IMG" || attribute.attrName !== "src") return;

  try {
    const url = new URL(attribute.attrValue, window.location.href);
    if (
      !["http:", "https:"].includes(url.protocol) ||
      url.origin !== window.location.origin
    ) {
      attribute.keepAttr = false;
    } else {
      // 固定解析基准，避免相对路径在写入 DOM 时指向其他来源。
      attribute.attrValue = url.href;
    }
  } catch {
    attribute.keepAttr = false;
  }
});

export const sanitizeModelHtml = (html) => sanitizer.sanitize(html, {
  ALLOWED_TAGS: [
    "a", "b", "blockquote", "br", "code", "del", "div", "em",
    "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img",
    "li", "ol", "p", "pre", "s", "span", "strong", "sub", "sup",
    "table", "tbody", "td", "th", "thead", "tr", "ul",
  ],
  // 不开放 style/srcset 等资源入口；代码复制仅保留专用 data-copy。
  ALLOWED_ATTR: [
    "href", "title", "src", "alt", "width", "height", "class", "id",
    "data-copy", "align", "start",
  ],
  ALLOW_DATA_ATTR: false,
  ALLOW_ARIA_ATTR: false,
});
