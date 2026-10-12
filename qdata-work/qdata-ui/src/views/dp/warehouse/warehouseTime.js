const formatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
});

// Older warehouse responses are UTC without an offset; intake callers identify their local offset.
export function formatWarehouseTime(value, legacyOffset = 'Z') {
  if (!value) return '—';
  let text = String(value).trim().replace(' ', 'T').replace(/(\.\d{3})\d+/, '$1');
  if (!/(?:Z|[+-]\d{2}:\d{2})$/i.test(text)) text += legacyOffset;
  const date = new Date(text);
  if (Number.isNaN(date.getTime())) return '—';
  const parts = Object.fromEntries(formatter.formatToParts(date).map(part => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
}
