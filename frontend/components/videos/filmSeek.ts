/**
 * The event a chapter fires to move the film, and its payload.
 *
 * <p>A window event rather than shared React state so the chapter list and the
 * transcript stay SERVER components: their text is the reason these pages
 * exist, and putting ~700 words through a client boundary would ship the whole
 * narration in the JS bundle as well as in the HTML.
 *
 * <p>Its own LEAF module so that `SeekButton`, which is rendered once per
 * chapter, does not have to import the player to name the event they share.
 * What keeps the narration out of the bundle is a separate decision, and it is
 * not this one: `_lib/youtube.ts` holds the embed URL so that `FilmPlayer` has
 * no reason to import the film registry either.
 */
export const FILM_SEEK_EVENT = 'lc-film-seek';

export interface FilmSeekDetail {
  /** Seconds from the start of the film. */
  seconds: number;
}
