# bidi

> "bidi bidi bidi" -- Twiki, in probably every episode of
  [Buck Rogers in the 25th Century](http://en.wikipedia.org/wiki/Buck_Rogers_in_the_25th_Century_%28TV_series%29)

In the grand tradition of Clojure libraries we begin with an irrelevant
quote.

Bi-directional URI dispatch. Like
[Compojure](https://github.com/weavejester/compojure), but when you want
to go both ways. For example, many routing libraries can route a URI to
a request handler, but only some of these (for example,
[Pedestal](http://pedestal.io),
[gudu](https://github.com/thatismatt/gudu)) can take a reference to a
handler, plus some environment, and generate a URI. If you are serving
REST resources, you should be
[providing links](http://en.wikipedia.org/wiki/HATEOAS) to other
resources, and without full support for generating URIs from handlers
your code will become coupled with your routing. In short, hard-coded
URIs will eventually break.

In bidi, routes are *data structures*, there are no macros here. Generally
speaking, data structures are to be preferred over code structures. When
routes are defined in a data structure there are numerous
advantages - they can be read in from a configuration file, generated,
computed, transformed by functions and introspected - all things which
macro-based DSLs make harder. This project also avoids 'terse' forms for
the route definitions, it is better to learn and live with a single data
structure.

The logic for matching routes is separated from the responsibility for
handling requests. This is an important
[architectural principle](http://www.infoq.com/presentations/Simple-Made-Easy). So
you can match on things that aren't necessarily handlers, like keywords
which you can use to lookup your handlers, or whatever you want to
do. Separation of concerns and all that.

## Installation

Add the following dependency to your `project.clj` file

```clojure
[bidi "1.3.0"]
```

## Take 5 minutes to learn bidi (using the REPL)

Let's create a route that matches `/index.html`. Routes are pairs, the
first element is always a pattern.

```clojure
user> (def route ["/index.html" :index])
```

Let's try to match that route to a path.

```clojure
user> (use 'bidi.bidi)
nil
user> (match-route "/index.html" route)
{:handler :index}
```

We have a match! A map is returned with a single entry with a `:handler`
key and `:index` as the value. We could use this result, for example, to
look up a Ring handler in a map mapping keywords to Ring handlers.

What happens if we try a different path?

```clojure
user> (match-route "/another.html" route)
nil
```

We get a `nil`. Nil means 'no route matched'.

Now, let's go in the other direction.

```clojure
user> (path-for :index route)
"/index.html"
```

We ask bidi to use the same route definition to tell us the path that
would match the `:index` hander. In this case, it tells us
`index.html`. So if you were generating a link to this handler from
another page, you could use this function in your view logic to create
the link instead of hardcoding in the view template (This gives your
code more resiliance to changes in the organisation of routes during
development).

### Mutliple routes

Let's pretend we have some articles in our blog and each article URI
matches the pattern `/articles/:id/index.html` where `:id` is the unique
article number. Rather than including 'special' characters in strings,
we construct the pattern in segments using a simple Clojure vector:
`["/articles/" :id "index.html"]`. We combine this route with our
existing `:index` route inside another vector.

```clojure
    user> (def routes ["/" [
            ["index.html" :index]
            [["articles/" :id "/article.html"] :article]
           ]])
    #'user/routes
```

Now we can match on an article path.

```clojure
user> (match-route "/articles/123/article.html" routes)
{:handler :article, :params {:id "123"}}
user> (match-route "/articles/999/article.html" routes)
{:handler :article, :params {:id "999"}}
```

The result is a map as before. This time it includes a `:params` entry
with a map of route parameters.

To generate the path we need to supply the value of `:id` as extra
arguments to the `path-for` function.

```clojure
user> (path-for :article routes :id 123)
"/articles/123/article.html"
user> (path-for :article routes :id 999)
"/articles/999/article.html"
```

Apart from a few extra bells and whistles documented in the rest of this
README, that's basically it.

## Wrapping as a Ring handler

You can use functions (or symbols that represent them) as pattern
targets. If you choose to do this a benefit it that you can wrap your
routes to form a Ring handler (similar to what Compojure's `routes` and
`defroutes` does).

```clojure
(require '[bidi.bidi :refer (make-handler)])

(def handler
  (make-handler ["/blog"
                 [["/index.html" blog-index]
                  [["/article/" :id ".html"] blog-article-handler]
                  [["/archive/" :id "/old.html"] (fn [req] {:status 404}]]]))
```

## Guards

### Method guards

By default, routes don't dispatch on the request method and behave like
Compojure's `ANY` routes. That's fine if your handlers deal with the
request methods themselves, as
[Liberator](http://clojure-liberator.github.io/liberator/)'s
do. However, you can specify a method by wrapping a route (or routes) in
a pair, where the first element is a keyword denoting the methods.

```clojure
["/"
 [["blog"
   [[:get [["/index" (fn [req] {:status 200 :body "Index"})]]]]]]]
```

### Other guards

You can also restrict routes by other criteria. In this example, the
`/zip` route is only matched if the server name in the request is
`juxt.pro`. Guards are specified by maps. Map entries can specify a
single value, a set of possible values or even a predicate to test a
value.

```clojure
["/"
 [["blog"
   [[:get [["/index" (fn [req] {:status 200 :body "Index"})]]]
    [{:request-method :post :server-name "juxt.pro"}
     [["/zip" (fn [req] {:status 201 :body "Created"})]]]]
   ]]]
```

## Route definitions

This [BNF](http://en.wikipedia.org/wiki/Backus%E2%80%93Naur_Form)
grammar describes the structure of the routes definition data
structures.

```
RoutePair ::= [Pattern Matched]

Pattern ::= Path | [ PatternSegment+ ] | MethodGuard | GeneralGuard

MethodGuard ::= :get :post :put :delete :head :options

GeneralGuard ::= [ GuardKey GuardValue ]* (a map)

GuardKey ::= Keyword

GuardValue ::= Value | Set | Function

Path ::= String

PatternSegment ::= String | Regex | Keyword | [ (String | Regex) Keyword ]

Matched ::= Function | Symbol | Keyword | RoutePair | [ RoutePair+ ]
```

## Composeability

Route structures are composeable. They are consistent and easy to
generate. A future version of bidi may contain macros to reduce the
number of parenthesis needed to create route structures by hand.

## Extensibility

The implementation is based on Clojure protocols which allows the route
syntax to be extended outside of this library.

## License

Copyright © 2013, JUXT LTD. All Rights Reserved.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
