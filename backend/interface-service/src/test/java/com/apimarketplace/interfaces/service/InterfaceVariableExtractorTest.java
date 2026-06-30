package com.apimarketplace.interfaces.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterfaceVariableExtractorTest {

    private InterfaceVariableExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new InterfaceVariableExtractor();
    }

    @Nested
    class ExtractTemplateVariables {

        @Test
        void shouldExtractMustacheVariable() {
            List<String> vars = extractor.extractTemplateVariables("Hello {{name}}!");
            assertThat(vars).containsExactly("name");
        }

        @Test
        void shouldExtractMustacheVariableWithDefault() {
            List<String> vars = extractor.extractTemplateVariables("{{title|Default Title}}");
            assertThat(vars).containsExactly("title");
        }

        @Test
        void shouldExtractDollarVariable() {
            List<String> vars = extractor.extractTemplateVariables("Value: ${amount}");
            assertThat(vars).containsExactly("amount");
        }

        @Test
        void shouldExtractDollarVariableWithDefault() {
            List<String> vars = extractor.extractTemplateVariables("${currency|USD}");
            assertThat(vars).containsExactly("currency");
        }

        @Test
        void shouldExtractMultipleVariables() {
            String template = "{{first_name}} {{last_name}} - ${email}";
            List<String> vars = extractor.extractTemplateVariables(template);
            assertThat(vars).containsExactly("first_name", "last_name", "email");
        }

        @Test
        void shouldDeduplicateVariables() {
            String template = "{{name}} says hello to {{name}}";
            List<String> vars = extractor.extractTemplateVariables(template);
            assertThat(vars).containsExactly("name");
        }

        @Test
        void shouldExtractFunctionFirstArgument() {
            String template = "{{formatDate(date, 'DD/MM/YYYY')}}";
            List<String> vars = extractor.extractTemplateVariables(template);
            assertThat(vars).containsExactly("date");
        }

        @Test
        void shouldExtractNestedFunctionArgument() {
            String template = "{{outer(inner(value, 'x'), 'y')}}";
            List<String> vars = extractor.extractTemplateVariables(template);
            assertThat(vars).containsExactly("value");
        }

        @Test
        void shouldSkipColonPrefixedVariables() {
            String template = "{{trigger:webhook}} {{name}}";
            List<String> vars = extractor.extractTemplateVariables(template);
            assertThat(vars).containsExactly("name");
        }

        @Test
        void shouldSkipDottedPathVariables() {
            String template = "{{user.name}} {{title}}";
            List<String> vars = extractor.extractTemplateVariables(template);
            assertThat(vars).containsExactly("title");
        }

        @Test
        void shouldSkipFunctionWithStringLiteralFirstArg() {
            String template = "{{formatDate('2024-01-01', 'DD/MM')}}";
            List<String> vars = extractor.extractTemplateVariables(template);
            assertThat(vars).isEmpty();
        }

        @Test
        void shouldReturnEmptyForNull() {
            assertThat(extractor.extractTemplateVariables(null)).isEmpty();
        }

        @Test
        void shouldReturnEmptyForEmptyString() {
            assertThat(extractor.extractTemplateVariables("")).isEmpty();
        }

        @Test
        void shouldReturnEmptyForNoVariables() {
            assertThat(extractor.extractTemplateVariables("<h1>Hello World</h1>")).isEmpty();
        }

        @Test
        void shouldProcessMustacheBeforeDollarSyntax() {
            // Mustache patterns are extracted first, then dollar patterns
            String template = "${zulu} {{alpha}} ${beta}";
            List<String> vars = extractor.extractTemplateVariables(template);
            assertThat(vars).containsExactly("alpha", "zulu", "beta");
        }

        @Test
        void shouldHandleMixedSyntaxWithDefaults() {
            String template = "{{greeting|Hello}} ${target|World}!";
            List<String> vars = extractor.extractTemplateVariables(template);
            assertThat(vars).containsExactly("greeting", "target");
        }

        @Test
        void shouldHandleCssVariables() {
            String template = "color: {{primaryColor}}; font-size: ${fontSize|14px};";
            List<String> vars = extractor.extractTemplateVariables(template);
            assertThat(vars).containsExactly("primaryColor", "fontSize");
        }

        @Test
        void shouldExtractFromMultilineTemplate() {
            String template = "<div>{{header}}</div>\n<p>{{content}}</p>\n<footer>${footer}</footer>";
            List<String> vars = extractor.extractTemplateVariables(template);
            assertThat(vars).containsExactly("header", "content", "footer");
        }

        @Test
        void shouldSkipWhitespaceOnlyPlaceholder() {
            List<String> vars = extractor.extractTemplateVariables("{{ }} {{ok}}");
            assertThat(vars).containsExactly("ok");
        }
    }

    @Nested
    class ExtractFormFields {

        @Test
        void shouldExtractInputFieldName() {
            String html = "<input name=\"username\" type=\"text\" />";
            List<String> fields = extractor.extractFormFields(html);
            assertThat(fields).containsExactly("username");
        }

        @Test
        void shouldExtractSelectFieldName() {
            String html = "<select name=\"country\"><option>US</option></select>";
            List<String> fields = extractor.extractFormFields(html);
            assertThat(fields).containsExactly("country");
        }

        @Test
        void shouldExtractTextareaFieldName() {
            String html = "<textarea name=\"message\"></textarea>";
            List<String> fields = extractor.extractFormFields(html);
            assertThat(fields).containsExactly("message");
        }

        @Test
        void shouldExtractMultipleFieldTypes() {
            String html = "<input name=\"email\" /><select name=\"role\"></select><textarea name=\"bio\"></textarea>";
            List<String> fields = extractor.extractFormFields(html);
            assertThat(fields).containsExactly("email", "role", "bio");
        }

        @Test
        void shouldDeduplicateFieldNames() {
            String html = "<input name=\"search\" /><input name=\"search\" />";
            List<String> fields = extractor.extractFormFields(html);
            assertThat(fields).containsExactly("search");
        }

        @Test
        void shouldHandleSingleQuotedNames() {
            String html = "<input name='field1' />";
            List<String> fields = extractor.extractFormFields(html);
            assertThat(fields).containsExactly("field1");
        }

        @Test
        void shouldReturnEmptyForNull() {
            assertThat(extractor.extractFormFields(null)).isEmpty();
        }

        @Test
        void shouldReturnEmptyForEmptyString() {
            assertThat(extractor.extractFormFields("")).isEmpty();
        }

        @Test
        void shouldReturnEmptyForNoFormElements() {
            assertThat(extractor.extractFormFields("<div>No forms here</div>")).isEmpty();
        }

        @Test
        void shouldHandleCaseInsensitiveTagNames() {
            String html = "<INPUT NAME=\"field\" /><SELECT NAME=\"opt\"></SELECT>";
            List<String> fields = extractor.extractFormFields(html);
            assertThat(fields).containsExactly("field", "opt");
        }
    }
}
