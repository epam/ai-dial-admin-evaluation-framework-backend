---
name: pr-summary
description: Use when the user wants to generate a pull/merge request (PR/MR) summary for the changes in the current branch
allowed-tools: Read Grep Glob LSP Bash(git diff:*) Bash(git log:*) Bash(git show:*) Bash(git rev-parse:*) Bash(git merge-base:*) Bash(git rev-list:*) Bash(mkdir -p ./tmp/pr-summary) Write(./tmp/pr-summary/*)
model: opus
effort: high
context: fork
agent: general-purpose
---

Provide an extremely succinct summary of the changes in the branch in exactly two paragraphs: the first starts with `**Problem:**` and states the problem; the second starts with `**Solution:**` and describes the solution. Put the md file into the tmp directory. File name should be <git-branch>-PR-summary.