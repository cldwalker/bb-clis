Title: {{title|safe}}
Date: {{date}}
Tags: {{tags|join:","}}
{% if preview %}Preview: true
{% endif %}{% if summary %}Logseq-summary: true
{% endif %}{% if logseq-tags %}Logseq-tags: {{logseq-tags|safe}}
{% endif %}{% if description %}Description: {{description|safe}}
{% endif %}{% if entities %}Entities: {{entities}}
{% endif %}{% comment %}
The body is an html block so markdown doesn't autolink url values inside <a>.
Html blocks end at a blank line, so the body must not contain one.
{% endcomment %}
{{body|safe}}
