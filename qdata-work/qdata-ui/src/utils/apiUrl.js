// 只归一化基址与接口路径的边界，保留协议、主机及基址内部路径。
export function joinApiPath(baseURL, path) {
  return `${(baseURL ?? '').replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`;
}
