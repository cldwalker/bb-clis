Title: {{title}}
Date: {{date}}
Tags: {{tags|join:","}}
Entities: {{entities}}
{% comment %}
The body is an html block so markdown doesn't autolink url values inside <a>.
Html blocks end at a blank line, so the body must not contain one.
{% endcomment %}
{{body|safe}}
