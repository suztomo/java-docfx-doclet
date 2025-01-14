package com.microsoft.lookup;

import com.microsoft.lookup.model.ExtendedMetadataFileItem;
import com.microsoft.model.ExceptionItem;
import com.microsoft.model.MetadataFileItem;
import com.microsoft.model.MethodParameter;
import com.microsoft.model.Return;
import com.microsoft.model.TypeParameter;
import com.microsoft.util.YamlUtil;
import com.sun.source.doctree.DeprecatedTree;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.LinkTree;
import com.sun.source.doctree.LiteralTree;
import com.sun.source.doctree.SeeTree;
import jdk.javadoc.doclet.DocletEnvironment;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class BaseLookup<T extends Element> {

    protected final Map<ElementKind, String> elementKindLookup = new HashMap<>() {{
        put(ElementKind.PACKAGE, "Namespace");
        put(ElementKind.CLASS, "Class");
        put(ElementKind.ENUM, "Enum");
        put(ElementKind.ENUM_CONSTANT, "Field");
        put(ElementKind.INTERFACE, "Interface");
        put(ElementKind.ANNOTATION_TYPE, "Interface");
        put(ElementKind.CONSTRUCTOR, "Constructor");
        put(ElementKind.METHOD, "Method");
        put(ElementKind.FIELD, "Field");
    }};

    protected Map<T, ExtendedMetadataFileItem> map = new HashMap<>();
    protected final DocletEnvironment environment;

    protected BaseLookup(DocletEnvironment environment) {
        this.environment = environment;
    }

    protected ExtendedMetadataFileItem resolve(T key) {
        ExtendedMetadataFileItem value = map.get(key);
        if (value == null) {
            value = buildMetadataFileItem(key);
            map.put(key, value);
        }
        return value;
    }

    protected abstract ExtendedMetadataFileItem buildMetadataFileItem(T key);

    public String extractPackageName(T key) {
        return resolve(key).getPackageName();
    }

    public String extractFullName(T key) {
        return resolve(key).getFullName();
    }

    public String extractName(T key) {
        return resolve(key).getName();
    }

    public String extractHref(T key) {
        return resolve(key).getHref();
    }

    public String extractParent(T key) {
        return resolve(key).getParent();
    }

    public String extractId(T key) {
        return resolve(key).getId();
    }

    public String extractUid(T key) {
        return resolve(key).getUid();
    }

    public String extractNameWithType(T key) {
        return resolve(key).getNameWithType();
    }

    public String extractMethodContent(T key) {
        return resolve(key).getMethodContent();
    }

    public String extractFieldContent(T key) {
        return resolve(key).getFieldContent();
    }

    public String extractConstructorContent(T key) {
        return resolve(key).getConstructorContent();
    }

    public String extractOverload(T key) {
        return resolve(key).getOverload();
    }

    public List<MethodParameter> extractParameters(T key) {
        return resolve(key).getParameters();
    }

    public List<ExceptionItem> extractExceptions(T key) {
        return resolve(key).getExceptions();
    }

    public Return extractReturn(T key) {
        return resolve(key).getReturn();
    }

    public String extractSummary(T key) {
        return resolve(key).getSummary();
    }

    public String extractType(T key) {
        return resolve(key).getType();
    }

    public String extractJavaType(T element) {return null;}

    public String extractContent(T key) {
        return resolve(key).getContent();
    }

    public List<TypeParameter> extractTypeParameters(T key) {
        return resolve(key).getTypeParameters();
    }

    public List<String> extractSuperclass(T key) {
        List<String> reversed = resolve(key).getSuperclass();
        Collections.reverse(reversed);
        return reversed;
    }

    public List<String> extractInheritedMethods(T key) {
        List<String> sorted = resolve(key).getInheritedMethods();
        Collections.sort(sorted);
        return sorted;
    }

    public List<String> extractInterfaces(T key) {
        return resolve(key).getInterfaces();
    }

    public String extractTocName(T key) {
        return resolve(key).getTocName();
    }

    public Set<MetadataFileItem> extractReferences(T key) {
        return resolve(key).getReferences();
    }

    public String extractOverridden(T key) {
        return resolve(key).getOverridden();
    }

    public String extractStatus(T element) {
        Optional<DocCommentTree> docCommentTree = getDocCommentTree(element);
        if (docCommentTree.isPresent()) {
            boolean isDeprecated = docCommentTree.get().getBlockTags().stream()
                    .filter(docTree -> docTree.getKind().equals(DocTree.Kind.DEPRECATED))
                    .findFirst()
                    .isPresent();
            if (isDeprecated) {
                return DocTree.Kind.DEPRECATED.name().toLowerCase();
            }
        }
        return null;
    }

    protected String determineType(T element) {
        return elementKindLookup.get(element.getKind());
    }

    protected String determinePackageName(T element) {
        return String.valueOf(environment.getElementUtils().getPackageOf(element));
    }

    protected String determineComment(T element) {
        Optional<DocCommentTree> docCommentTree = getDocCommentTree(element);
        if (docCommentTree.isPresent()) {
            String comment = docCommentTree
                    .map(DocCommentTree::getFullBody)
                    .map(this::replaceLinksAndCodes)
                    .orElse(null);
            return replaceBlockTags(docCommentTree.get(), comment);
        }
        return null;
    }

    /**
     * Provides support for deprecated and see tags
     */
    String replaceBlockTags(DocCommentTree docCommentTree, String comment) {
        Set<String> seeItems = new HashSet<>();
        String commentWithBlockTags = comment;
        for (DocTree blockTag : docCommentTree.getBlockTags()) {
            switch (blockTag.getKind()) {
                case DEPRECATED:
                    commentWithBlockTags = getDeprecatedSummary((DeprecatedTree) blockTag).concat(comment);
                    break;
                case SEE:
                    seeItems.add(getSeeTagRef((SeeTree) blockTag));
                    break;
                default:
            }
        }
        if (!seeItems.isEmpty()) {
            commentWithBlockTags = commentWithBlockTags.concat(getSeeAlsoSummary(seeItems));
        }
        return commentWithBlockTags;
    }

    /**
     * <ul>
     * <li>Replace @link and @linkplain with <xref> tags</li>
     * <li>Replace @code with <code> tags</li>
     * </ul>
     */
    String replaceLinksAndCodes(List<? extends DocTree> items) {
        return YamlUtil.cleanupHtml(items.stream().map(
                bodyItem -> {
                    switch (bodyItem.getKind()) {
                        case LINK:
                        case LINK_PLAIN:
                            return buildXrefTag((LinkTree) bodyItem);
                        case CODE:
                            return buildCodeTag((LiteralTree) bodyItem);
                        case LITERAL:
                            return expandLiteralBody((LiteralTree) bodyItem);
                        default:
                            return String.valueOf(bodyItem);
                    }
                }
        ).collect(Collectors.joining()));
    }

    /**
     * By using this way of processing links we provide support of @links with label, like this: {@link List someLabel}
     */
    String buildXrefTag(LinkTree linkTree) {
        String signature = linkTree.getReference().getSignature();
        String label = linkTree.getLabel().stream().map(String::valueOf).collect(Collectors.joining(" "));
        if (StringUtils.isEmpty(label)) {
            label = signature;
        }
        return String.format("<xref uid=\"%s\" data-throw-if-not-resolved=\"false\">%s</xref>", signature, label);
    }

    String buildCodeTag(LiteralTree literalTree) {
        return String.format("<code>%s</code>", literalTree.getBody());
    }

    String expandLiteralBody(LiteralTree bodyItem) {
        return String.valueOf(bodyItem.getBody());
    }

    protected Optional<DocCommentTree> getDocCommentTree(T element) {
        return Optional.ofNullable(environment.getDocTrees().getDocCommentTree(element));
    }

    /**
     * We make type shortening in assumption that package name doesn't contain uppercase characters
     */
    public String makeTypeShort(String value) {
        if (!value.contains(".")) {
            return value;
        }
        return Stream.of(StringUtils.split(value, "<"))
                .map(s -> RegExUtils.removeAll(s, "\\b[a-z0-9_.]+\\."))
                .collect(Collectors.joining("<"));
    }

    private String getSeeAlsoSummary(Set<String> seeItems) {
        return String.format("\nSee Also: %s\n", String.join(", ", seeItems));
    }

    private String getDeprecatedSummary(DeprecatedTree deprecatedTree) {
        return String.format("\n<strong>Deprecated.</strong> <em>%s</em>\n\n",
                replaceLinksAndCodes(deprecatedTree.getBody()));
    }

    private String getSeeTagRef(SeeTree seeTree) {
        String ref = seeTree.getReference().stream()
                .map(r -> String.valueOf(r)).collect(Collectors.joining(""));
        // if it's already a tag, use that otherwise build xref tag
        if (ref.matches("^<.+>(.|\n)*")) {
            return ref.replaceAll("\n", "").replaceAll("(  )+", " ");
        }
        return String.format("<xref uid=\"%1$s\" data-throw-if-not-resolved=\"false\">%1$s</xref>", ref);
    }
}
