package com.grid07.repository;

import com.grid07.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PostRepository extends JpaRepository<Post, Long> {
    List<Post> findByAuthorIdAndAuthorTypeOrderByCreatedAtDesc(
        Long authorId, Post.AuthorType authorType);
}
