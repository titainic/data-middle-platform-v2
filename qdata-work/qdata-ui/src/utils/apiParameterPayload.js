const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function apiParameterPayload(rows, resetIds = false) {
  const walk = (items, parent) => (items || []).map((item) => {
    const output = { ...item };
    if (resetIds || (typeof output.id === 'string' && uuid.test(output.id))) delete output.id;
    if (resetIds) delete output.apiId;
    output.parentId = parent ? (parent.id ?? null) : 0;
    output.daAssetApiParamList = walk(item.daAssetApiParamList, output);
    return output;
  });
  return walk(rows, null);
}
