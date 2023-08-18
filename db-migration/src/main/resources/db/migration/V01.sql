--
-- PostgreSQL database dump
--

-- Dumped from database version 13.10

--
-- Name: auditlog; Type: TABLE; Schema: public; Owner: emojimanagerdev
--

CREATE TABLE public.auditlog (
                                 id uuid NOT NULL,
                                 date timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                 actor_id character varying(64) NOT NULL,
                                 action character varying(256) NOT NULL,
                                 proposal uuid,
                                 emoji character varying(256),
                                 note text
);


--
-- Name: emojifiles; Type: TABLE; Schema: public; Owner: emojimanagerdev
--

CREATE TABLE public.emojifiles (
                                   sha1 character varying(40) NOT NULL,
                                   file bytea NOT NULL,
                                   content_type character varying(32) NOT NULL
);

--
-- Name: emojis; Type: TABLE; Schema: public; Owner: emojimanagerdev
--

CREATE TABLE public.emojis (
                               name character varying(256) NOT NULL,
                               file character varying(40),
                               alias boolean DEFAULT false NOT NULL,
                               cname character varying(256),
                               updated timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                               proposal uuid NOT NULL
);


--
-- Name: proposalpreviews; Type: TABLE; Schema: public; Owner: emojimanagerdev
--

CREATE TABLE public.proposalpreviews (
                                         file_id character varying(32) NOT NULL,
                                         preview_url text NOT NULL,
                                         proposal uuid
);


--
-- Name: proposals; Type: TABLE; Schema: public; Owner: emojimanagerdev
--

CREATE TABLE public.proposals (
                                  id uuid NOT NULL,
                                  created timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                                  state character varying(25) DEFAULT 'new'::character varying NOT NULL,
                                  action character varying(25) DEFAULT 'add'::character varying NOT NULL,
                                  emoji character varying(256) NOT NULL,
                                  file character varying(40),
                                  alias boolean DEFAULT false NOT NULL,
                                  cname character varying(256),
                                  thread character varying(256) NOT NULL,
                                  permalink text,
                                  "user" character varying(64) NOT NULL,
                                  preview_file character varying(64),
                                  preview_url text
);


--
-- Name: auditlog auditlog_pkey; Type: CONSTRAINT; Schema: public; Owner: emojimanagerdev
--

ALTER TABLE ONLY public.auditlog
    ADD CONSTRAINT auditlog_pkey PRIMARY KEY (id);


--
-- Name: emojifiles emojifiles_pkey; Type: CONSTRAINT; Schema: public; Owner: emojimanagerdev
--

ALTER TABLE ONLY public.emojifiles
    ADD CONSTRAINT emojifiles_pkey PRIMARY KEY (sha1);


--
-- Name: emojis emojis_pkey; Type: CONSTRAINT; Schema: public; Owner: emojimanagerdev
--

ALTER TABLE ONLY public.emojis
    ADD CONSTRAINT emojis_pkey PRIMARY KEY (name);


--
-- Name: proposalpreviews proposalpreviews_pkey; Type: CONSTRAINT; Schema: public; Owner: emojimanagerdev
--

ALTER TABLE ONLY public.proposalpreviews
    ADD CONSTRAINT proposalpreviews_pkey PRIMARY KEY (file_id);


--
-- Name: proposals proposals_pkey; Type: CONSTRAINT; Schema: public; Owner: emojimanagerdev
--

ALTER TABLE ONLY public.proposals
    ADD CONSTRAINT proposals_pkey PRIMARY KEY (id);


--
-- Name: auditlog fk_auditlog_proposal_id; Type: FK CONSTRAINT; Schema: public; Owner: emojimanagerdev
--

ALTER TABLE ONLY public.auditlog
    ADD CONSTRAINT fk_auditlog_proposal_id FOREIGN KEY (proposal) REFERENCES public.proposals(id) ON UPDATE RESTRICT ON DELETE RESTRICT;


--
-- Name: emojis fk_emojis_file_sha1; Type: FK CONSTRAINT; Schema: public; Owner: emojimanagerdev
--

ALTER TABLE ONLY public.emojis
    ADD CONSTRAINT fk_emojis_file_sha1 FOREIGN KEY (file) REFERENCES public.emojifiles(sha1) ON UPDATE RESTRICT ON DELETE RESTRICT;


--
-- Name: emojis fk_emojis_proposal_id; Type: FK CONSTRAINT; Schema: public; Owner: emojimanagerdev
--

ALTER TABLE ONLY public.emojis
    ADD CONSTRAINT fk_emojis_proposal_id FOREIGN KEY (proposal) REFERENCES public.proposals(id) ON UPDATE RESTRICT ON DELETE RESTRICT;


--
-- Name: proposalpreviews fk_proposalpreviews_proposal_id; Type: FK CONSTRAINT; Schema: public; Owner: emojimanagerdev
--

ALTER TABLE ONLY public.proposalpreviews
    ADD CONSTRAINT fk_proposalpreviews_proposal_id FOREIGN KEY (proposal) REFERENCES public.proposals(id) ON UPDATE RESTRICT ON DELETE RESTRICT;


--
-- Name: proposals fk_proposals_file_sha1; Type: FK CONSTRAINT; Schema: public; Owner: emojimanagerdev
--

ALTER TABLE ONLY public.proposals
    ADD CONSTRAINT fk_proposals_file_sha1 FOREIGN KEY (file) REFERENCES public.emojifiles(sha1) ON UPDATE RESTRICT ON DELETE RESTRICT;


--
-- PostgreSQL database dump complete
--