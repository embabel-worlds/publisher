package com.embabel.publisher.realm;

import java.util.ArrayList;
import java.util.List;

/**
 * The realm's configuration: its metadata (protocol §3.7), where it is served, and how the world
 * authenticates. A plain JavaBean, so Spring Boot binds it from {@code embabel.realm.*} and any
 * other stack can fill it by hand.
 */
public class RealmSettings {

    /** Publication is on unless turned off (protocol §2.2). */
    private boolean enabled = true;

    private String name;
    private String title;
    private String description;
    private String version;
    private String author;
    private String url;
    private String category;
    private List<String> tags = new ArrayList<>();
    private String maturity;

    /** The path the publisher is served at, relative to the application's context path. */
    private String path = "/embabel";

    private Auth auth = new Auth();
    private ActingUser actingUser = new ActingUser();

    public static class Auth {

        /**
         * The world's bearer credential. Set it from the application's secret configuration, for
         * example the {@code EMBABEL_REALM_AUTH_TOKEN} environment variable; never commit it. With
         * none set, every call is refused: the publisher fails closed.
         */
        private String token;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    public static class ActingUser {

        /** Protocol §3.4. This version of the library supports {@code ignored} only. */
        private String mode = "ignored";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public String getMaturity() {
        return maturity;
    }

    public void setMaturity(String maturity) {
        this.maturity = maturity;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public ActingUser getActingUser() {
        return actingUser;
    }

    public void setActingUser(ActingUser actingUser) {
        this.actingUser = actingUser;
    }
}
