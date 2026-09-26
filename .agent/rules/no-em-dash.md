# Permanent style rule: no em dashes

Never use the em dash character (U+2014) or the en dash character (U+2013) in any file created or edited in this project. This covers UI strings, code, comments, docs, commit messages, and chat replies.

Use one of these instead:
- a hyphen-minus (-) for ranges and compounds, e.g. 04:00-07:30
- a comma, colon, or parentheses to set off a clause
- the middle dot (·) as a metadata separator in UI chips

Before finishing any task, search the touched files for U+2014 and U+2013 (excluding third-party and dataset folders) and replace every hit.
