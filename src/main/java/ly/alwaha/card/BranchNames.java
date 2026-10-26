package ly.alwaha.card;

import java.util.Locale;
import java.util.Map;

/**
 * Suggested Arabic names for the English branch names getBranches returns ("Zliten Branch" → "فرع زليتن").
 * SUGGESTIONS ONLY: the dashboard pre-fills them, an admin checks the official spelling and saves.
 * Nothing here is ever printed on a card unless it has been saved in the branch table.
 * Unknown names get no suggestion (rather than a bad guess).
 */
public final class BranchNames {

    // Libyan cities and Tripoli areas, under the English spellings commonly used for them.
    private static final Map<String, String> PLACES = Map.ofEntries(
            Map.entry("tripoli", "طرابلس"), Map.entry("benghazi", "بنغازي"),
            Map.entry("misrata", "مصراتة"), Map.entry("misurata", "مصراتة"),
            Map.entry("zliten", "زليتن"), Map.entry("zletin", "زليتن"),
            Map.entry("zawia", "الزاوية"), Map.entry("zawiya", "الزاوية"), Map.entry("zawiyah", "الزاوية"),
            Map.entry("sooq eljoma", "سوق الجمعة"), Map.entry("souq eljoma", "سوق الجمعة"),
            Map.entry("souq aljuma", "سوق الجمعة"), Map.entry("suq al jumaa", "سوق الجمعة"),
            Map.entry("souq al jumaa", "سوق الجمعة"), Map.entry("sooq aljomaa", "سوق الجمعة"),
            Map.entry("souq althulatha", "سوق الثلاثاء"), Map.entry("sooq altholatha", "سوق الثلاثاء"),
            Map.entry("tajoura", "تاجوراء"), Map.entry("tajura", "تاجوراء"),
            Map.entry("janzour", "جنزور"), Map.entry("janzur", "جنزور"),
            Map.entry("gharyan", "غريان"), Map.entry("gharian", "غريان"),
            Map.entry("khoms", "الخمس"), Map.entry("alkhoms", "الخمس"), Map.entry("al khoms", "الخمس"), Map.entry("al khums", "الخمس"),
            Map.entry("sabha", "سبها"), Map.entry("sebha", "سبها"),
            Map.entry("sirte", "سرت"), Map.entry("sirt", "سرت"),
            Map.entry("ajdabiya", "اجدابيا"), Map.entry("ajdabia", "اجدابيا"),
            Map.entry("bayda", "البيضاء"), Map.entry("albayda", "البيضاء"), Map.entry("al bayda", "البيضاء"),
            Map.entry("derna", "درنة"), Map.entry("tobruk", "طبرق"),
            Map.entry("zuwara", "زوارة"), Map.entry("zuwarah", "زوارة"),
            Map.entry("sabratha", "صبراتة"), Map.entry("surman", "صرمان"), Map.entry("sorman", "صرمان"),
            Map.entry("ghadames", "غدامس"), Map.entry("bani walid", "بني وليد"), Map.entry("beni walid", "بني وليد"),
            Map.entry("tarhuna", "ترهونة"), Map.entry("tarhouna", "ترهونة"),
            Map.entry("msallata", "مسلاتة"), Map.entry("msalata", "مسلاتة"),
            Map.entry("yefren", "يفرن"), Map.entry("yafran", "يفرن"), Map.entry("nalut", "نالوت"),
            Map.entry("kufra", "الكفرة"), Map.entry("murzuq", "مرزق"), Map.entry("ubari", "أوباري"),
            Map.entry("ghat", "غات"), Map.entry("brak", "براك"), Map.entry("hun", "هون"), Map.entry("waddan", "ودان"),
            Map.entry("jadu", "جادو"), Map.entry("zintan", "الزنتان"),
            Map.entry("marj", "المرج"), Map.entry("almarj", "المرج"), Map.entry("al marj", "المرج"),
            Map.entry("brega", "البريقة"), Map.entry("ras lanuf", "راس لانوف"), Map.entry("jalu", "جالو"),
            Map.entry("garabulli", "القره بوللي"), Map.entry("aziziya", "العزيزية"), Map.entry("al aziziya", "العزيزية"),
            Map.entry("hay alandalus", "حي الأندلس"), Map.entry("hay al andalus", "حي الأندلس"), Map.entry("andalus", "الأندلس"),
            Map.entry("ben ashour", "بن عاشور"), Map.entry("bin ashour", "بن عاشور"),
            Map.entry("gargaresh", "قرقارش"), Map.entry("gergarish", "قرقارش"),
            Map.entry("dahra", "الظهرة"), Map.entry("ain zara", "عين زارة"), Map.entry("ain zarah", "عين زارة"),
            Map.entry("abu salim", "أبو سليم"), Map.entry("abusleem", "أبو سليم"), Map.entry("abu sleem", "أبو سليم"),
            Map.entry("fashloum", "فشلوم"), Map.entry("gurji", "قرجي"), Map.entry("gorji", "قرجي"),
            Map.entry("hadba", "الهضبة"), Map.entry("airport road", "طريق المطار"), Map.entry("omar mukhtar", "عمر المختار"),
            Map.entry("qasr bin ghashir", "قصر بن غشير"), Map.entry("mitiga", "معيتيقة"), Map.entry("shahat", "شحات"),
            Map.entry("susa", "سوسة"), Map.entry("qubba", "القبة"), Map.entry("zawiyat aldahmani", "زاوية الدهماني"));

    private BranchNames() {}

    /** A suggested Arabic name, or null when there's no confident match. */
    public static String suggest(String english) {
        if (english == null) return null;
        String n = english.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").strip();
        if (n.equals("alwaha bank") || n.equals("al waha bank")) return "مصرف الواحة";
        if (n.equals("main branch") || n.equals("head office")) return "الفرع الرئيسي";
        if (n.endsWith(" branch")) n = n.substring(0, n.length() - " branch".length()).strip();
        String place = PLACES.get(n);
        return place == null ? null : "فرع " + place;
    }
}
