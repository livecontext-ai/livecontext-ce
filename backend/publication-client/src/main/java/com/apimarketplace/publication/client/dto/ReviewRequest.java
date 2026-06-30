package com.apimarketplace.publication.client.dto;

public class ReviewRequest {
    private Short rating;
    private String comment;

    public ReviewRequest() {
    }

    public Short getRating() {
        return rating;
    }

    public void setRating(Short rating) {
        this.rating = rating;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
