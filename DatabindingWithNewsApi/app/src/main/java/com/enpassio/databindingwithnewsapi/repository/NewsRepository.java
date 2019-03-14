package com.enpassio.databindingwithnewsapi.repository;

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MutableLiveData;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;

import com.enpassio.databindingwithnewsapi.model.Article;
import com.enpassio.databindingwithnewsapi.utils.Constants;
import com.enpassio.databindingwithnewsapi.utils.NetworkUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

public class NewsRepository {

    private static NewsRepository sInstance;
    private final MutableLiveData<List<Article>> articles = new MutableLiveData<>();
    private static final String TAG = "NewsRepository";
    private final Context mContext;
    private MutableLiveData<Boolean> isInternetConnected = new MutableLiveData<>();

    private NewsRepository(Context context) {
        Log.d(TAG, "New instance created");
        mContext = context;
    }

    public static NewsRepository getInstance(Context context) {
        if (sInstance == null) {
            synchronized (NewsRepository.class) {
                sInstance = new NewsRepository(context);
            }
        }
        return sInstance;
    }

    public void checkConnectionAndStartFetching() {
        //If data is already there, no need to go over this process again
        if (articles.getValue() != null && !articles.getValue().isEmpty()) {
            return;
        }

        if (NetworkUtils.thereIsConnection(mContext)) {
            //Pass network state to fragment
            Log.d(TAG, "there is connection, start fetching");
            //Start fetching from the News Api in a background thread
            new NewsAsyncTask().execute();
            isInternetConnected.setValue(true);
        } else {
            Log.d(TAG, "there is no connection");
            //Pass network state to fragment
            isInternetConnected.setValue(false);
        }
    }

    public LiveData<List<Article>> getArticles() {
        return articles;
    }

    public LiveData<Boolean> getConnectionStatus() {
        return isInternetConnected;
    }

    private class NewsAsyncTask extends AsyncTask<Void, Void, List<Article>> {

        @Override
        protected List<Article> doInBackground(Void... voids) {
            return fetchArticles();
        }

        @Override
        protected void onPostExecute(List<Article> list) {
            articles.setValue(list);
        }

        private List<Article> fetchArticles() {
            // Create URL object
            URL url = buildUrl();

            // Perform HTTP request to the URL and receive a JSON response back
            String jsonResponse = null;
            try {
                jsonResponse = makeHttpRequest(url);
            } catch (IOException e) {
                Log.e(TAG, "Problem making the HTTP request.", e);
            }

            // Return the list of {@link Article}s
            return extractFeatureFromJson(jsonResponse);
        }

        /**
         * Returns new URL object from the given string URL.
         */

        private URL buildUrl() {
            Uri uri = Uri.parse(Constants.BASE_URL).buildUpon()
                    .appendPath(Constants.ENDPOINT)
                    .appendQueryParameter(Constants.CATEGORY, Constants.SAMPLE_CATEGORY)
                    .appendQueryParameter(Constants.COUNTRY, Constants.SAMPLE_COUNTRY)
                    .appendQueryParameter(Constants.PAGE_SIZE_PARAM, Constants.SAMPLE_PAGE_SIZE)
                    .appendQueryParameter(Constants.NEWS_API_KEY, Constants.NEWS_API_VALUE)
                    .build();

            URL url = null;
            try {
                url = new URL(uri.toString());
                Log.d(TAG, "The build URL: " + uri.toString());
            } catch (MalformedURLException e) {
                Log.e(TAG, "Problem building the URL ", e);
            }
            return url;
        }

        /**
         * Make an HTTP request to the given URL and return a String as the response.
         */
        private String makeHttpRequest(URL url) throws IOException {
            String jsonResponse = "";

            // If the URL is null, then return early.
            if (url == null) {
                return jsonResponse;
            }

            HttpURLConnection urlConnection = null;
            InputStream inputStream = null;
            try {
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setReadTimeout(10000 /* milliseconds */);
                urlConnection.setConnectTimeout(15000 /* milliseconds */);
                urlConnection.setRequestMethod("GET");
                urlConnection.connect();

                // If the request was successful (response code 200),
                // then read the input stream and parse the response.
                if (urlConnection.getResponseCode() == 200) {
                    isInternetConnected.postValue(true);
                    inputStream = urlConnection.getInputStream();
                    jsonResponse = readFromStream(inputStream);
                } else {
                    isInternetConnected.postValue(false);
                    Log.e(TAG, "Error response code: " + urlConnection.getResponseCode());
                }
            } catch (IOException e) {
                Log.e(TAG, "Problem retrieving the news JSON results.", e);
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
                if (inputStream != null) {
                    // Closing the input stream could throw an IOException, which is why
                    // the makeHttpRequest(URL url) method signature specifies than an IOException
                    // could be thrown.
                    inputStream.close();
                }
            }
            return jsonResponse;
        }

        /**
         * Convert the {@link InputStream} into a String which contains the
         * whole JSON response from the server.
         */
        private String readFromStream(InputStream inputStream) throws IOException {
            StringBuilder output = new StringBuilder();
            if (inputStream != null) {
                InputStreamReader inputStreamReader = new InputStreamReader(inputStream, Charset.forName("UTF-8"));
                BufferedReader reader = new BufferedReader(inputStreamReader);
                String line = reader.readLine();
                while (line != null) {
                    output.append(line);
                    line = reader.readLine();
                }
            }
            return output.toString();
        }

        /**
         * Return a list of {@link Article} objects that has been built up from
         * parsing the given JSON response.
         */
        private List<Article> extractFeatureFromJson(String articlesJSON) {
            // If the JSON string is empty or null, then return early.
            if (TextUtils.isEmpty(articlesJSON)) {
                return null;
            }

            // Create an empty ArrayList that we can start adding articles to
            List<Article> articles = new ArrayList<>();

            // Try to parse the JSON response string. If there's a problem with the way the JSON
            // is formatted, a JSONException exception object will be thrown.
            // Catch the exception so the app doesn't crash, and print the error message to the logs.
            try {

                // Create a JSONObject from the JSON response string
                JSONObject baseJsonResponse = new JSONObject(articlesJSON);

                // Extract the JSONArray associated with the key called "articles",
                // which represents a list of articles.
                JSONArray articlesArray = baseJsonResponse.getJSONArray(Constants.ARTICLES_ARRAY);

                // For each article in the articlesArray, create an {@link Article} object
                for (int i = 0; i < articlesArray.length(); i++) {

                    // Get a single article at position i within the list of articles
                    JSONObject currentArticle = articlesArray.getJSONObject(i);

                    //Retrieve the field that you need from this json object:

                    // Extract the value for the key called "author"
                    String author = currentArticle.getString(Constants.AUTHOR);

                    // Extract the value for the key called "title"
                    String title = currentArticle.getString(Constants.TITLE);

                    // Extract the value for the key called "description"
                    String description = currentArticle.getString(Constants.DESCRIPTION);

                    // Extract the value for the article url
                    String articleUrl = currentArticle.getString(Constants.ARTICLE_URL);

                    // Extract the value for the image url
                    String imageUrl = currentArticle.getString(Constants.IMAGE_URL);

                    //Extract the value for the key "publishedAt"
                    String publishingTime = formatDateTime(currentArticle.getString(Constants.PUBLISHING_TIME));

                    //Extract the value for the key "content"
                    String articleBody = currentArticle.getString(Constants.ARTICLE_BODY);

                    //Source name is inside a source json object, so we first need to get this object
                    JSONObject sourceJSON = currentArticle.getJSONObject(Constants.SOURCE);

                    //Then we get the source name from this sourceJSON
                    String sourceName = sourceJSON.getString(Constants.SOURCE_NAME);

                    // Create a new {@link Article} object with
                    Article article = new Article(sourceName, author, title, description, articleUrl, imageUrl, publishingTime, articleBody);

                    // Add the new {@link Article} to the list of articles.
                    articles.add(article);
                }

            } catch (JSONException e) {
                // If an error is thrown when executing any of the above statements in the "try" block,
                // catch the exception here, so the app doesn't crash. Print a log message
                // with the message from the exception.
                Log.e(TAG, "Problem parsing the NewsApi JSON results", e);
            }

            // Return the list of articles
            return articles;
        }

        private String formatDateTime(String dateTime) {
            TimeZone timeZone = TimeZone.getTimeZone("UTC");
            SimpleDateFormat sourceFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
            sourceFormat.setTimeZone(timeZone);
            Date parsedTime = null;
            try {
                parsedTime = sourceFormat.parse(dateTime);
            } catch (ParseException e) {
                e.printStackTrace();
            }

            TimeZone tz = TimeZone.getDefault();
            SimpleDateFormat destFormat = new SimpleDateFormat("LLL dd, yyyy'T'HH:mm");
            destFormat.setTimeZone(tz);
            return destFormat.format(parsedTime);
        }
    }
}
