package com.estradaplay.comunista;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves destinations while preferring the requested municipality/state over similarly named districts. */
final class DestinationResolver {
    private static final String BASE = "https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=br&addressdetails=1&namedetails=1&dedupe=1&limit=10";
    private static final Pattern UF_SUFFIX = Pattern.compile(
            "(?i)^(.*?)(?:\\s*[-,/]\\s*|\\s+)(AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO)\\s*$");
    private static final Map<String, String> STATES = new HashMap<>();

    static {
        STATES.put("AC", "Acre"); STATES.put("AL", "Alagoas"); STATES.put("AP", "Amapá");
        STATES.put("AM", "Amazonas"); STATES.put("BA", "Bahia"); STATES.put("CE", "Ceará");
        STATES.put("DF", "Distrito Federal"); STATES.put("ES", "Espírito Santo"); STATES.put("GO", "Goiás");
        STATES.put("MA", "Maranhão"); STATES.put("MT", "Mato Grosso"); STATES.put("MS", "Mato Grosso do Sul");
        STATES.put("MG", "Minas Gerais"); STATES.put("PA", "Pará"); STATES.put("PB", "Paraíba");
        STATES.put("PR", "Paraná"); STATES.put("PE", "Pernambuco"); STATES.put("PI", "Piauí");
        STATES.put("RJ", "Rio de Janeiro"); STATES.put("RN", "Rio Grande do Norte"); STATES.put("RS", "Rio Grande do Sul");
        STATES.put("RO", "Rondônia"); STATES.put("RR", "Roraima"); STATES.put("SC", "Santa Catarina");
        STATES.put("SP", "São Paulo"); STATES.put("SE", "Sergipe"); STATES.put("TO", "Tocantins");
    }

    private DestinationResolver() {}

    static List<DestinationStore.Destination> search(String query) throws Exception {
        QueryIntent intent = QueryIntent.parse(query);
        if (intent.original.length() < 3) return new ArrayList<>();

        ArrayList<Candidate> candidates = new ArrayList<>();

        // "Cidade - UF" gets a municipality-first structured lookup. This avoids a district or village
        // with a longer matching name becoming the first route destination.
        if (intent.cityLike && intent.uf != null) {
            String structured = BASE
                    + "&city=" + enc(intent.primary)
                    + "&state=" + enc(intent.stateName)
                    + "&country=" + enc("Brasil");
            addCandidates(candidates, fetch(structured), intent);
            if (bestScore(candidates) >= 300) return destinations(candidates);
        }

        String freeQuery = intent.primary;
        if (intent.uf != null) freeQuery += ", " + intent.stateName;
        freeQuery += ", Brasil";
        addCandidates(candidates, fetch(BASE + "&q=" + enc(freeQuery)), intent);
        return destinations(candidates);
    }

    /** Unit-test hook: applies the exact same ranking without making a network request. */
    static List<DestinationStore.Destination> rankJsonForTest(String query, String raw) throws Exception {
        QueryIntent intent = QueryIntent.parse(query);
        ArrayList<Candidate> candidates = new ArrayList<>();
        addCandidates(candidates, new JSONArray(raw), intent);
        return destinations(candidates);
    }

    private static JSONArray fetch(String target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(9000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9");
        c.setRequestProperty("User-Agent", "EstradaPlay/5.1 Android destination-search");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new Exception("HTTP " + code);
        }
        String raw;
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bytes.write(buf, 0, n);
                if (bytes.size() > 2_000_000) throw new Exception("Resposta grande demais");
            }
            raw = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
        return new JSONArray(raw);
    }

    private static void addCandidates(List<Candidate> out, JSONArray arr, QueryIntent intent) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            double lat = parse(item.optString("lat", ""));
            double lon = parse(item.optString("lon", ""));
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) continue;
            String label = item.optString("display_name", "Destino").trim();
            out.add(new Candidate(new DestinationStore.Destination(label, lat, lon), score(item, intent)));
        }
    }

    private static int score(JSONObject item, QueryIntent intent) {
        JSONObject address = item.optJSONObject("address");
        if (address == null) address = new JSONObject();

        String wanted = norm(intent.primary);
        String name = norm(item.optString("name", ""));
        if (name.isEmpty()) {
            JSONObject named = item.optJSONObject("namedetails");
            if (named != null) name = norm(named.optString("name", ""));
        }
        String display = norm(item.optString("display_name", ""));
        String type = norm(item.optString("type", ""));
        String addressType = norm(item.optString("addresstype", ""));

        int score = 0;
        if (!wanted.isEmpty()) {
            if (name.equals(wanted)) score += 160;
            else if (name.startsWith(wanted + " ")) score += 45;
            else if (name.contains(wanted)) score += 12;
            if (display.startsWith(wanted + " ") || display.equals(wanted)) score += 35;
        }

        boolean exactMunicipality = false;
        for (String key : new String[]{"city", "town", "municipality"}) {
            String value = norm(address.optString(key, ""));
            if (!value.isEmpty() && value.equals(wanted)) {
                exactMunicipality = true;
                score += 110;
                break;
            }
        }

        if (isOneOf(addressType, "city", "town", "municipality")) score += 95;
        else if ("administrative".equals(type) && exactMunicipality) score += 65;

        if (intent.cityLike) {
            if (isOneOf(addressType, "village", "hamlet", "suburb", "neighbourhood", "quarter", "city district", "district")) score -= 110;
            if (isOneOf(addressType, "state", "state district", "region")) score -= 130;
            if (isOneOf(addressType, "road", "house", "building", "amenity", "shop", "tourism")) score -= 30;
        }

        if (intent.uf != null) {
            String foundUf = extractUf(item, address);
            String foundState = norm(address.optString("state", ""));
            String wantedState = norm(intent.stateName);
            if (intent.uf.equals(foundUf) || (!wantedState.isEmpty() && wantedState.equals(foundState))) score += 140;
            else if ((!foundUf.isEmpty() && !intent.uf.equals(foundUf)) || (!foundState.isEmpty() && !wantedState.equals(foundState))) score -= 320;
        }

        double importance = item.optDouble("importance", 0.0);
        if (Double.isFinite(importance)) score += (int)Math.round(Math.max(0.0, Math.min(1.0, importance)) * 25.0);
        return score;
    }

    private static String extractUf(JSONObject item, JSONObject address) {
        for (String key : new String[]{"ISO3166-2-lvl4", "ISO3166-2-lvl3", "state_code"}) {
            String value = address.optString(key, "").trim().toUpperCase(Locale.ROOT);
            if (value.isEmpty()) value = item.optString(key, "").trim().toUpperCase(Locale.ROOT);
            if (value.matches("BR-[A-Z]{2}")) return value.substring(3);
            if (value.matches("[A-Z]{2}")) return value;
        }
        return "";
    }

    private static List<DestinationStore.Destination> destinations(List<Candidate> raw) {
        raw.sort(Comparator.comparingInt((Candidate c) -> c.score).reversed());
        ArrayList<DestinationStore.Destination> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Candidate c : raw) {
            String key = Math.round(c.destination.lat * 100000.0) + ":" + Math.round(c.destination.lon * 100000.0);
            if (!seen.add(key)) continue;
            out.add(c.destination);
            if (out.size() >= 8) break;
        }
        return out;
    }

    private static int bestScore(List<Candidate> list) {
        int best = Integer.MIN_VALUE;
        for (Candidate c : list) best = Math.max(best, c.score);
        return best;
    }

    private static boolean isOneOf(String value, String... options) {
        for (String option : options) if (option.equals(value)) return true;
        return false;
    }

    private static String enc(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }

    private static String norm(String value) {
        if (value == null) return "";
        String s = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static double parse(String value) {
        try { return Double.parseDouble(value); } catch (Throwable ignored) { return Double.NaN; }
    }

    private static final class Candidate {
        final DestinationStore.Destination destination;
        final int score;
        Candidate(DestinationStore.Destination destination, int score) { this.destination = destination; this.score = score; }
    }

    private static final class QueryIntent {
        final String original;
        final String primary;
        final String uf;
        final String stateName;
        final boolean cityLike;

        QueryIntent(String original, String primary, String uf, String stateName, boolean cityLike) {
            this.original = original;
            this.primary = primary;
            this.uf = uf;
            this.stateName = stateName;
            this.cityLike = cityLike;
        }

        static QueryIntent parse(String raw) {
            String original = raw == null ? "" : raw.trim();
            String primary = original;
            String uf = null;
            String state = null;
            Matcher m = UF_SUFFIX.matcher(original);
            if (m.matches()) {
                String possible = m.group(1) == null ? "" : m.group(1).trim();
                String found = m.group(2) == null ? "" : m.group(2).toUpperCase(Locale.ROOT);
                if (!possible.isEmpty() && STATES.containsKey(found)) {
                    primary = possible;
                    uf = found;
                    state = STATES.get(found);
                }
            }
            String n = norm(primary);
            boolean hasDigit = n.matches(".*\\d.*");
            boolean obviousAddressOrPoi = n.matches(".*\\b(rua|r|avenida|av|rodovia|br|estrada|travessa|alameda|shopping|hotel|posto|hospital|aeroporto|igreja|restaurante|praia|parque)\\b.*");
            int words = n.isEmpty() ? 0 : n.split(" ").length;
            boolean cityLike = !hasDigit && !obviousAddressOrPoi && words > 0 && words <= 4;
            return new QueryIntent(original, primary, uf, state, cityLike);
        }
    }
}
