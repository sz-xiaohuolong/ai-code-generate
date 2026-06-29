export type VisualSelectedElement = {
  tagName: string
  selector: string
  text: string
  id?: string
  className?: string
  attributes: Record<string, string>
  rect: {
    x: number
    y: number
    width: number
    height: number
  }
}

type VisualEditorBridgeOptions = {
  getIframe: () => HTMLIFrameElement | null
  onElementSelected: (element: VisualSelectedElement) => void
  onError?: (message: string) => void
}

const MESSAGE_SOURCE = 'ai-code-visual-editor'
const SELECTED_MESSAGE_TYPE = 'element-selected'
const ENABLE_MESSAGE_TYPE = 'enable'
const DISABLE_MESSAGE_TYPE = 'disable'
const CLEAR_MESSAGE_TYPE = 'clear'

const visualEditorScript = `
(() => {
  if (window.__AI_CODE_VISUAL_EDITOR_INSTALLED__) {
    return;
  }
  window.__AI_CODE_VISUAL_EDITOR_INSTALLED__ = true;

  const MESSAGE_SOURCE = '${MESSAGE_SOURCE}';
  const SELECTED_MESSAGE_TYPE = '${SELECTED_MESSAGE_TYPE}';
  const ENABLE_MESSAGE_TYPE = '${ENABLE_MESSAGE_TYPE}';
  const DISABLE_MESSAGE_TYPE = '${DISABLE_MESSAGE_TYPE}';
  const CLEAR_MESSAGE_TYPE = '${CLEAR_MESSAGE_TYPE}';
  let enabled = false;
  let hoveredElement = null;
  let selectedElement = null;

  const style = document.createElement('style');
  style.textContent = \`
    .ai-code-visual-editor-hover {
      outline: 2px dashed #1677ff !important;
      outline-offset: 2px !important;
      cursor: crosshair !important;
    }
    .ai-code-visual-editor-selected {
      outline: 3px solid #0958d9 !important;
      outline-offset: 2px !important;
      cursor: crosshair !important;
    }
  \`;
  document.head.appendChild(style);

  const getElementSelector = (element) => {
    if (!element || element.nodeType !== Node.ELEMENT_NODE) {
      return '';
    }
    if (element.id) {
      return '#' + CSS.escape(element.id);
    }
    const path = [];
    let current = element;
    while (current && current.nodeType === Node.ELEMENT_NODE && current !== document.body) {
      const tagName = current.tagName.toLowerCase();
      const classNames = Array.from(current.classList || [])
        .filter(Boolean)
        .slice(0, 3)
        .map((name) => '.' + CSS.escape(name))
        .join('');
      const siblings = Array.from(current.parentElement?.children || [])
        .filter((item) => item.tagName === current.tagName);
      const nth = siblings.length > 1 ? ':nth-of-type(' + (siblings.indexOf(current) + 1) + ')' : '';
      path.unshift(tagName + classNames + nth);
      current = current.parentElement;
    }
    return path.join(' > ');
  };

  const getElementInfo = (element) => {
    const rect = element.getBoundingClientRect();
    const attributes = {};
    ['href', 'src', 'alt', 'title', 'aria-label', 'placeholder', 'type', 'name'].forEach((name) => {
      const value = element.getAttribute(name);
      if (value) {
        attributes[name] = value;
      }
    });
    return {
      tagName: element.tagName.toLowerCase(),
      selector: getElementSelector(element),
      text: (element.innerText || element.textContent || '').trim().replace(/\\s+/g, ' ').slice(0, 200),
      id: element.id || undefined,
      className: element.className && typeof element.className === 'string' ? element.className : undefined,
      attributes,
      rect: {
        x: Math.round(rect.x),
        y: Math.round(rect.y),
        width: Math.round(rect.width),
        height: Math.round(rect.height),
      },
    };
  };

  const clearHover = () => {
    if (hoveredElement && hoveredElement !== selectedElement) {
      hoveredElement.classList.remove('ai-code-visual-editor-hover');
    }
    hoveredElement = null;
  };

  const clearSelected = () => {
    if (selectedElement) {
      selectedElement.classList.remove('ai-code-visual-editor-selected');
    }
    selectedElement = null;
  };

  const onMouseOver = (event) => {
    if (!enabled) {
      return;
    }
    const target = event.target;
    if (!target || target === document.documentElement || target === document.body) {
      return;
    }
    clearHover();
    hoveredElement = target;
    if (hoveredElement !== selectedElement) {
      hoveredElement.classList.add('ai-code-visual-editor-hover');
    }
  };

  const onMouseOut = () => {
    if (!enabled) {
      return;
    }
    clearHover();
  };

  const onClick = (event) => {
    if (!enabled) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    const target = event.target;
    if (!target || target === document.documentElement || target === document.body) {
      return;
    }
    clearSelected();
    selectedElement = target;
    selectedElement.classList.add('ai-code-visual-editor-selected');
    window.parent.postMessage(
      {
        source: MESSAGE_SOURCE,
        type: SELECTED_MESSAGE_TYPE,
        payload: getElementInfo(selectedElement),
      },
      '*',
    );
  };

  const setEnabled = (value) => {
    enabled = value;
    document.documentElement.style.cursor = value ? 'crosshair' : '';
    if (!value) {
      clearHover();
      clearSelected();
    }
  };

  document.addEventListener('mouseover', onMouseOver, true);
  document.addEventListener('mouseout', onMouseOut, true);
  document.addEventListener('click', onClick, true);
  window.addEventListener('message', (event) => {
    if (event.data?.source !== MESSAGE_SOURCE) {
      return;
    }
    if (event.data.type === ENABLE_MESSAGE_TYPE) {
      setEnabled(true);
    }
    if (event.data.type === DISABLE_MESSAGE_TYPE) {
      setEnabled(false);
    }
    if (event.data.type === CLEAR_MESSAGE_TYPE) {
      clearHover();
      clearSelected();
    }
  });
})();
`

export const formatVisualElementForPrompt = (element: VisualSelectedElement) => {
  const attributes = Object.entries(element.attributes)
    .map(([key, value]) => `${key}="${value}"`)
    .join(' ')
  return [
    '用户在可视化编辑模式下选中了页面元素，请优先结合该元素理解修改范围：',
    `- 标签：${element.tagName}`,
    `- 选择器：${element.selector}`,
    element.id ? `- id：${element.id}` : '',
    element.className ? `- class：${element.className}` : '',
    element.text ? `- 文本内容：${element.text}` : '',
    attributes ? `- 关键属性：${attributes}` : '',
    `- 位置尺寸：x=${element.rect.x}, y=${element.rect.y}, width=${element.rect.width}, height=${element.rect.height}`,
  ]
    .filter(Boolean)
    .join('\\n')
}

export const createVisualEditorBridge = ({
  getIframe,
  onElementSelected,
  onError,
}: VisualEditorBridgeOptions) => {
  const postMessageToIframe = (type: string) => {
    const iframe = getIframe()
    iframe?.contentWindow?.postMessage(
      {
        source: MESSAGE_SOURCE,
        type,
      },
      '*',
    )
  }

  const injectScript = () => {
    const iframe = getIframe()
    if (!iframe?.contentWindow) {
      onError?.('请先生成并展示网站后再进入编辑模式')
      return false
    }
    try {
      const iframeWindow = iframe.contentWindow as Window & {
        __AI_CODE_VISUAL_EDITOR_INSTALLED__?: boolean
      }
      if (iframeWindow.__AI_CODE_VISUAL_EDITOR_INSTALLED__) {
        return true
      }
      const iframeDocument = iframe.contentDocument
      if (!iframeDocument?.documentElement) {
        onError?.('预览页面还没有加载完成')
        return false
      }
      const script = iframeDocument.createElement('script')
      script.textContent = visualEditorScript
      ;(iframeDocument.body || iframeDocument.documentElement).appendChild(script)
      script.remove()
      return true
    } catch (error) {
      onError?.('无法进入可视化编辑模式，请确认预览页面和主站同源')
      return false
    }
  }

  const handleMessage = (event: MessageEvent) => {
    const iframe = getIframe()
    if (!iframe?.contentWindow || event.source !== iframe.contentWindow) {
      return
    }
    if (event.data?.source !== MESSAGE_SOURCE || event.data.type !== SELECTED_MESSAGE_TYPE) {
      return
    }
    onElementSelected(event.data.payload)
  }

  window.addEventListener('message', handleMessage)

  return {
    enable() {
      if (!injectScript()) {
        return false
      }
      postMessageToIframe(ENABLE_MESSAGE_TYPE)
      return true
    },
    disable() {
      postMessageToIframe(DISABLE_MESSAGE_TYPE)
    },
    clearSelection() {
      postMessageToIframe(CLEAR_MESSAGE_TYPE)
    },
    refresh() {
      if (injectScript()) {
        postMessageToIframe(ENABLE_MESSAGE_TYPE)
      }
    },
    dispose() {
      postMessageToIframe(DISABLE_MESSAGE_TYPE)
      window.removeEventListener('message', handleMessage)
    },
  }
}
