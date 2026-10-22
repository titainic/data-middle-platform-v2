// 新配置只允许近期显式年份；历史表达式打开、取消及原样回填不改写。
export const MAX_YEAR_AHEAD = 5;

export function isYearWithinHorizon(value, currentYear = new Date().getFullYear()) {
    if (!value || value === '*') return true;
    const maxYear = currentYear + MAX_YEAR_AHEAD;
    return value.split(',').every(part => {
        const match = /^(\d{4})(?:([/-])(\d+))?$/.exec(part);
        if (!match) return false;
        const start = Number(match[1]);
        if (start < 1970 || start > maxYear) return false;
        if (!match[2]) return true;
        const end = Number(match[3]);
        return match[2] === '-' ? end >= start && end <= maxYear : end >= 1 && end <= MAX_YEAR_AHEAD;
    });
}
