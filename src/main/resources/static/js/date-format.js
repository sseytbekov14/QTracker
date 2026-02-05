/* global window */
(function () {
    function toNumber(value) {
        var num = Number(value);
        return Number.isInteger(num) ? num : null;
    }

    function buildDate(year, month, day, hours, minutes, seconds) {
        var date = new Date(
            year,
            month - 1,
            day,
            hours || 0,
            minutes || 0,
            seconds || 0
        );
        if (
            date.getFullYear() !== year ||
            date.getMonth() + 1 !== month ||
            date.getDate() !== day
        ) {
            return null;
        }
        return date;
    }

    function parseIsoDate(value) {
        if (typeof value !== "string") return null;
        var match = value.trim().match(/^(\d{4})-(\d{2})-(\d{2})$/);
        if (!match) return null;
        var year = toNumber(match[1]);
        var month = toNumber(match[2]);
        var day = toNumber(match[3]);
        if (!year || !month || !day) return null;
        return buildDate(year, month, day);
    }

    function parseIsoDateTime(value) {
        if (typeof value !== "string") return null;
        var match = value
            .trim()
            .match(/^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?)?/);
        if (!match) return null;
        var year = toNumber(match[1]);
        var month = toNumber(match[2]);
        var day = toNumber(match[3]);
        var hours = toNumber(match[4] || "0");
        var minutes = toNumber(match[5] || "0");
        var seconds = toNumber(match[6] || "0");
        if (
            year === null ||
            month === null ||
            day === null ||
            hours === null ||
            minutes === null ||
            seconds === null
        ) {
            return null;
        }
        return buildDate(year, month, day, hours, minutes, seconds);
    }

    function formatDisplayDate(date) {
        if (!(date instanceof Date) || Number.isNaN(date.getTime())) return "";
        var day = String(date.getDate()).padStart(2, "0");
        var month = String(date.getMonth() + 1).padStart(2, "0");
        return day + "." + month + "." + date.getFullYear();
    }

    function formatDisplayDateFromIso(value) {
        var date = parseIsoDate(value) || parseIsoDateTime(value);
        return date ? formatDisplayDate(date) : "";
    }

    function formatDisplayDateTimeFromIso(value) {
        var date = parseIsoDateTime(value) || parseIsoDate(value);
        if (!date) return "";
        var hh = String(date.getHours()).padStart(2, "0");
        var mm = String(date.getMinutes()).padStart(2, "0");
        return formatDisplayDate(date) + " " + hh + ":" + mm;
    }

    function toIsoDate(date) {
        if (!(date instanceof Date) || Number.isNaN(date.getTime())) return "";
        var year = date.getFullYear();
        var month = String(date.getMonth() + 1).padStart(2, "0");
        var day = String(date.getDate()).padStart(2, "0");
        return year + "-" + month + "-" + day;
    }

    function parseDisplayDate(value) {
        if (typeof value !== "string") return null;
        var match = value.trim().match(/^(\d{1,2})\.(\d{1,2})\.(\d{4})$/);
        if (!match) return null;
        var day = toNumber(match[1]);
        var month = toNumber(match[2]);
        var year = toNumber(match[3]);
        if (!day || !month || !year) return null;
        return buildDate(year, month, day);
    }

    window.QTrackerDate = {
        formatDateDisplay: formatDisplayDateFromIso,
        parseIsoDate: parseIsoDate,
        parseIsoDateTime: parseIsoDateTime,
        parseDisplayDate: parseDisplayDate,
        formatDisplayDate: formatDisplayDate,
        formatDisplayDateFromIso: formatDisplayDateFromIso,
        formatDisplayDateTimeFromIso: formatDisplayDateTimeFromIso,
        toIsoDate: toIsoDate
    };
})();
