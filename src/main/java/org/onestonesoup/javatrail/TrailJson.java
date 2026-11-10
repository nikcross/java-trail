package org.onestonesoup.javatrail;

import java.util.*;
import java.util.regex.Pattern;
import org.onestonesoup.core.data.EntityTree;

/** Strict JSON boundary using the retained core data model. No dependency on a server or scripting engine. */
public final class TrailJson {
    private TrailJson() { }
    private static final String TYPE="jsonType";
    public static EntityTree parseObject(String name,String json) {
        if(json==null) throw new IllegalArgumentException("Missing JSON "+name);
        EntityTree tree=new EntityTree(name);
        Parser parser=new Parser(json);
        parser.value(tree.getRoot(),0); parser.whitespace();
        if(parser.position!=json.length() || !"object".equals(tree.getRoot().getAttribute(TYPE)))
            throw new IllegalArgumentException("Expected a JSON object for "+name);
        return tree;
    }
    public static String stringifyObject(EntityTree.TreeEntity root) {
        StringBuilder out=new StringBuilder(); write(root,out,true); return out.toString();
    }
    private static void write(EntityTree.TreeEntity node,StringBuilder out,boolean root) {
        String type=node.getAttribute(TYPE);
        if(node.getAttribute("array")!=null || "array".equals(type)) {
            out.append('['); boolean first=true;
            for(EntityTree.TreeEntity child:node.getChildren()) { if(!first)out.append(','); first=false; write(child,out,false); }
            out.append(']');
        } else if(root || "object".equals(type) || node.hasChildren()) {
            out.append('{'); boolean first=true;
            for(EntityTree.TreeEntity child:node.getChildren()) {
                if(!first)out.append(','); first=false; quote(child.getName(),out); out.append(':'); write(child,out,false);
            }
            out.append('}');
        } else if(node.getValue()==null) out.append("null");
        else if("number".equals(type) || "boolean".equals(type)) {
            // Overrides may have changed a typed value; reject invalid literals on the next parse.
            String value=node.getValue();
            if(("number".equals(type) && Parser.NUMBER.matcher(value).matches()) || ("boolean".equals(type) && (value.equals("true")||value.equals("false")))) out.append(value);
            else quote(value,out);
        } else quote(node.getValue(),out);
    }
    private static void quote(String value,StringBuilder out) {
        out.append('"');
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            switch(c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if(c<32 || Character.isSurrogate(c)) out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
                    else out.append(c);
            }
        }
        out.append('"');
    }
    private static final class Parser {
        static final Pattern NUMBER=Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
        final String json; int position;
        Parser(String json) {this.json=json;}
        IllegalArgumentException error(String message) {return new IllegalArgumentException(message+" at JSON offset "+position);}
        void whitespace() {while(position<json.length() && " \t\n\r".indexOf(json.charAt(position))>=0)position++;}
        boolean take(char c) {whitespace();if(position<json.length() && json.charAt(position)==c){position++;return true;}return false;}
        void expect(char c) {if(!take(c))throw error("Expected '"+c+"'");}
        void value(EntityTree.TreeEntity node,int depth) {
            whitespace(); if(depth>128)throw error("JSON nesting exceeds 128");
            if(position>=json.length())throw error("Missing value");
            char c=json.charAt(position);
            if(c=='{') {
                position++;node.setAttribute(TYPE,"object");Set<String> names=new HashSet<>();
                if(take('}'))return;
                do {
                    whitespace();String name=string();if(!names.add(name))throw error("Duplicate key "+name);
                    expect(':');value(node.addChild(name),depth+1);
                }while(take(','));expect('}');
            }else if(c=='[') {
                position++;node.setAttribute(TYPE,"array");node.setAttribute("array","true");int index=0;
                if(!take(']')) { do {value(node.addChild(Integer.toString(index++)),depth+1);}while(take(','));expect(']'); }
                node.setAttribute("length",Integer.toString(index));
            }else if(c=='"') {node.setAttribute(TYPE,"string");node.setValue(string());}
            else {
                int start=position;
                while(position<json.length() && ",]} \t\n\r".indexOf(json.charAt(position))<0)position++;
                String literal=json.substring(start,position);
                if(literal.equals("null"))node.setAttribute(TYPE,"null");
                else if(literal.equals("true")||literal.equals("false")){node.setAttribute(TYPE,"boolean");node.setValue(literal);}
                else if(NUMBER.matcher(literal).matches()){node.setAttribute(TYPE,"number");node.setValue(literal);}
                else throw error("Invalid literal");
            }
        }
        String string() {
            expect('"');StringBuilder value=new StringBuilder();
            while(position<json.length()) {
                char c=json.charAt(position++);
                if(c=='"')return value.toString();
                if(c<32)throw error("Unescaped control character");
                if(c!='\\'){value.append(c);continue;}
                if(position>=json.length())throw error("Incomplete escape");
                char escaped=json.charAt(position++);
                switch(escaped) {
                    case '"': case '\\': case '/': value.append(escaped);break;
                    case 'b':value.append('\b');break;case 'f':value.append('\f');break;
                    case 'n':value.append('\n');break;case 'r':value.append('\r');break;case 't':value.append('\t');break;
                    case 'u':
                        if(position+4>json.length())throw error("Incomplete unicode escape");
                        try{value.append((char)Integer.parseInt(json.substring(position,position+4),16));}catch(NumberFormatException e){throw error("Invalid unicode escape");}
                        position+=4;break;
                    default:throw error("Invalid string escape");
                }
            }
            throw error("Unterminated string");
        }
    }
}
